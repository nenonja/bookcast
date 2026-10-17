package com.bookcast.app.generation.adapter.openai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.application.Capabilities.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class TextFailureTest {
  @TempDir Path data;

  TextRequest request() {
    return new TextRequest(
        UUID.randomUUID(), UUID.randomUUID(), 0, List.of(), List.of(), true, true, "en", "fi");
  }

  TextResult response(int status, String body, String retryAfter) throws Exception {
    var adapter = new OpenAiText(new ObjectMapper(), "offline-only", data.toString());
    HttpClient http = mock(HttpClient.class);
    ReflectionTestUtils.setField(adapter, "http", http);
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                retryAfter == null ? Map.of() : Map.of("Retry-After", List.of(retryAfter)),
                (a, b) -> true));
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    var result = adapter.generate(request());
    verify(http, times(1)).send(any(), any());
    return result;
  }

  @Test
  void transientHttpStatusesAndRetryAfterArePreservedEvenWithoutJsonBody() throws Exception {
    for (int status : List.of(429, 500, 501, 502, 503, 504, 599)) {
      var m = response(status, "provider unavailable", "12").metadata();
      assertTrue(m.retryable());
      assertEquals(status, m.providerStatus());
      assertEquals("PROVIDER_HTTP_" + status, m.errorCode());
      assertEquals(12000L, m.retryAfterMs());
      assertTrue(m.usage().isEmpty());
      assertNull(m.cost());
    }
  }

  @Test
  void quotaAndInvalidRequestsAreNotTransient() throws Exception {
    for (String code : List.of("insufficient_quota", "billing_hard_limit_reached")) {
      var m =
          response(
                  429,
                  "{\"error\":{\"code\":\"" + code + "\",\"message\":\"unsafe source\"}}",
                  null)
              .metadata();
      assertFalse(m.retryable());
      assertEquals("PROVIDER_QUOTA", m.errorCode());
      assertEquals(code, m.providerCode());
    }
    for (int status : List.of(400, 401, 403, 404, 422))
      assertFalse(response(status, "{}", null).metadata().retryable());
    assertFalse(
        response(503, "{\"error\":{\"type\":\"invalid_request_error\"}}", null)
            .metadata()
            .retryable());
  }

  @Test
  void failedHttpResponseKeepsReportedUsageAndSafeCode() throws Exception {
    var m =
        response(
                503,
                "{\"usage\":{\"input_tokens\":50,\"output_tokens\":10},\"error\":{\"code\":\"service_unavailable\",\"message\":\"private source\"}}",
                null)
            .metadata();
    assertEquals(50L, m.usage().get("inputTokens"));
    assertNotNull(m.cost());
    assertEquals("service_unavailable", m.providerCode());
    assertFalse(m.toString().contains("private source"));
  }

  @Test
  void parseAndIncompleteOutputsDoNotAutomaticallyRetry() throws Exception {
    assertFalse(response(200, "broken json", null).metadata().retryable());
    var m =
        response(
                200,
                "{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":50,\"output_tokens\":10}}",
                null)
            .metadata();
    assertEquals("INCOMPLETE_DIALOGUE", m.errorCode());
    assertFalse(m.retryable());
    assertNotNull(m.cost());
    var invalid =
        response(
                200,
                "{\"status\":\"completed\",\"usage\":{\"input_tokens\":50,\"output_tokens\":10},\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"invalid candidate\"}]}]}",
                null)
            .metadata();
    assertFalse(invalid.retryable());
    assertNotNull(invalid.cost());
  }

  @Test
  void timeoutAndNetworkRetryButLocalAndTlsFailuresDoNot() throws Exception {
    for (Exception error :
        List.of(
            new HttpTimeoutException("private"),
            new java.net.SocketException("private"),
            new javax.net.ssl.SSLHandshakeException("private"),
            new IllegalArgumentException("private"))) {
      var adapter = new OpenAiText(new ObjectMapper(), "offline-only", data.toString());
      HttpClient http = mock(HttpClient.class);
      ReflectionTestUtils.setField(adapter, "http", http);
      when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(error);
      var m = adapter.generate(request()).metadata();
      assertEquals(
          error instanceof HttpTimeoutException || error instanceof java.net.SocketException,
          m.retryable());
      assertFalse(m.toString().contains("private"));
      assertNull(m.cost());
    }
  }
}
