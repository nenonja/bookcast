package com.bookcast.app.generation.adapter.openai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.ChapterSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class ProviderProtocolTest {
  @TempDir Path data;
  final ObjectMapper json = new ObjectMapper();

  @Test
  void realTextAdapterParsesStructuredFixtureAndRetainsUsage() throws Exception {
    var adapter = new OpenAiText(json, "offline-only", data.toString());
    HttpClient http = mock(HttpClient.class);
    ReflectionTestUtils.setField(adapter, "http", http);
    var response = mock(HttpResponse.class);
    UUID block = UUID.randomUUID();
    var segment =
        new Segment(
            0,
            List.of(block),
            List.of(new Turn(Speaker.HOST, "Miksi?"), new Turn(Speaker.EXPERT, "Siksi.")));
    String payload =
        json.writeValueAsString(
            Map.of(
                "status",
                "completed",
                "usage",
                Map.of("input_tokens", 100, "output_tokens", 200),
                "output",
                List.of(
                    Map.of(
                        "type",
                        "message",
                        "content",
                        List.of(
                            Map.of(
                                "type",
                                "output_text",
                                "text",
                                json.writeValueAsString(segment)))))));
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(payload);
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    var request =
        new TextRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            List.of(new ChapterSource.Block(block, 0, "Example source.", null)),
            List.of(),
            true,
            true,
            "en",
            "fi");
    var result = adapter.generate(request);
    assertEquals(segment, result.segment());
    assertEquals(100, result.metadata().usage().get("inputTokens"));
    assertNotNull(result.metadata().cost());
    assertTrue(
        Files.exists(
            data.resolve(request.generationId().toString())
                .resolve(request.attemptId().toString())
                .resolve("dialogue-candidate.json")));
    verify(http, times(1)).send(any(), any());
  }

  @Test
  void textHttpFailureKeepsUsageAndRetryClassification() throws Exception {
    var adapter = new OpenAiText(json, "offline-only", data.toString());
    HttpClient http = mock(HttpClient.class);
    ReflectionTestUtils.setField(adapter, "http", http);
    var response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(429);
    when(response.body())
        .thenReturn(
            "{\"error\":{\"message\":\"private source must not escape\"},\"usage\":{\"input_tokens\":50,\"output_tokens\":0}}");
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    var result =
        adapter.generate(
            new TextRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                List.of(),
                List.of(),
                true,
                true,
                "en",
                "fi"));
    assertEquals("PROVIDER_HTTP_429", result.metadata().errorCode());
    assertTrue(result.metadata().retryable());
    assertEquals(50, result.metadata().usage().get("inputTokens"));
    assertNull(result.segment());
  }

  @Test
  void realtimeFixtureUsesIsolatedContextVoicesAndSpeedAndPreservesPCM() throws Exception {
    checkAudio("completed", null);
  }

  @Test
  void incompleteRealtimeFixtureDoesNotPassAndRetainsPartialAudioUsage() throws Exception {
    checkAudio("incomplete", "INCOMPLETE_AUDIO");
  }

  @Test
  void structuredAudioErrorsNeverUseProviderMessagesForRetryClassification() throws Exception {
    for (String code :
        List.of(
            "insufficient_quota",
            "credit_balance_exhausted",
            "organization_usage_limit_exceeded",
            "invalid_api_key",
            "invalid_request_error",
            "unsupported_model",
            "unknown_error")) {
      var m =
          OpenAiAudioFailures.event(
              json.readTree(
                  "{\"code\":\""
                      + code
                      + "\",\"message\":\"rate limit timeout server error private content\"}"));
      assertFalse(m.retryable());
      assertEquals(code, m.providerCode());
    }
    var rateLimit = OpenAiAudioFailures.event(json.readTree("{\"code\":\"rate_limit_exceeded\"}"));
    assertTrue(rateLimit.retryable());
    assertEquals(429, rateLimit.providerStatus());
    assertTrue(OpenAiAudioFailures.event(json.readTree("{\"type\":\"server_error\"}")).retryable());
    assertFalse(
        OpenAiAudioFailures.event(
                json.readTree("{\"type\":\"insufficient_quota\",\"code\":\"rate_limit_exceeded\"}"))
            .retryable());
    assertTrue(OpenAiAudioFailures.exception(new java.net.SocketException("ignored")).retryable());
    assertTrue(OpenAiAudioFailures.exception(new TimeoutException()).retryable());
    assertFalse(OpenAiAudioFailures.exception(new IllegalArgumentException("ignored")).retryable());
  }

  @Test
  void handshakeStatusAndRetryAfterAreSafeAndStructured() {
    for (int status : List.of(400, 401, 403, 429, 500, 502, 503, 504)) {
      var response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(status);
      when(response.headers())
          .thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("12")), (a, b) -> true));
      var metadata =
          OpenAiAudioFailures.exception(
              new ExecutionException(new WebSocketHandshakeException(response)));
      assertEquals(status, metadata.providerStatus());
      assertEquals(Set.of(429, 500, 502, 503, 504).contains(status), metadata.retryable());
      assertEquals(12000L, metadata.retryAfterMs());
      assertNull(metadata.cost());
      assertTrue(metadata.usage().isEmpty());
    }
    var now = java.time.Instant.parse("2026-10-10T12:00:00Z");
    assertEquals(12000L, OpenAiAudioFailures.retryAfter("Sat, 10 Oct 2026 12:00:12 GMT", now));
    assertNull(OpenAiAudioFailures.retryAfter("invalid", now));
  }

  private void checkAudio(String status, String expectedError) throws Exception {
    var adapter = new OpenAiSpeech(json, "offline-only", data.toString());
    HttpClient http = mock(HttpClient.class);
    ReflectionTestUtils.setField(adapter, "http", http);
    var builder = mock(WebSocket.Builder.class, RETURNS_SELF);
    var socket = mock(WebSocket.class);
    when(http.newWebSocketBuilder()).thenReturn(builder);
    when(socket.sendText(any(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
    List<String> commands = new ArrayList<>();
    doAnswer(
            i -> {
              commands.add(i.getArgument(0).toString());
              return CompletableFuture.completedFuture(socket);
            })
        .when(socket)
        .sendText(any(), eq(true));
    when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
        .thenAnswer(
            i -> {
              WebSocket.Listener listener = i.getArgument(1);
              listener.onOpen(socket);
              String[] events = {
                "{\"type\":\"session.updated\",\"session\":{\"model\":\"gpt-realtime-2.1-mini\",\"audio\":{\"output\":{\"voice\":\"ash\",\"speed\":0.95}}}}",
                "{\"type\":\"response.output_audio.delta\",\"delta\":\"AQACAA==\"}",
                "{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"Selitys.\"}",
                "{\"type\":\"response.done\",\"response\":{\"status\":\""
                    + status
                    + "\",\"output\":[{\"role\":\"assistant\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"input_token_details\":{\"text_tokens\":10},\"output_token_details\":{\"text_tokens\":2,\"audio_tokens\":18}}}}"
              };
              for (String e : events) listener.onText(socket, e, true);
              return CompletableFuture.completedFuture(socket);
            });
    UUID job = UUID.randomUUID(), attempt = UUID.randomUUID();
    var result =
        adapter.render(
            new Group(0, 0, Speaker.EXPERT, "Selitys.", "hash", List.of()), job, attempt);
    assertEquals(expectedError, result.metadata().errorCode());
    assertArrayEquals(new byte[] {1, 0, 2, 0}, result.pcm());
    assertEquals("Selitys.", result.transcript());
    assertEquals(18, result.metadata().usage().get("outputAudioTokens"));
    assertEquals(
        .95,
        json.readTree(commands.get(0))
            .path("session")
            .path("audio")
            .path("output")
            .path("speed")
            .asDouble());
    assertEquals(
        "none", json.readTree(commands.get(1)).path("response").path("conversation").asText());
    assertArrayEquals(
        result.pcm(),
        Files.readAllBytes(
            data.resolve(job.toString()).resolve(attempt.toString()).resolve("stream.pcm")));
    verify(builder, times(1)).buildAsync(any(), any());
    verify(socket).abort();
  }
}
