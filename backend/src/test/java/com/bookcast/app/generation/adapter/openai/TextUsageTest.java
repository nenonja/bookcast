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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class TextUsageTest {
  @TempDir Path data;
  final ObjectMapper json = new ObjectMapper();

  Metadata response(int status, String usage, String model) throws Exception {
    var adapter = new OpenAiText(json, "offline-only", data.toString(), model);
    HttpClient http = mock(HttpClient.class);
    ReflectionTestUtils.setField(adapter, "http", http);
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    var body = json.createObjectNode().put("status", "completed");
    body.putArray("output")
        .addObject()
        .putArray("content")
        .addObject()
        .put("type", "output_text")
        .put(
            "text",
            """
            {"order":0,"coveredSourceBlockIds":[],"dialogueTurns":[{"speakerRole":"HOST","text":"Miksi?"},{"speakerRole":"EXPERT","text":"Siksi."}]}
            """);
    if (usage != null) body.set("usage", json.readTree(usage));
    if (status != 200) body.set("error", json.readTree("{\"code\":\"rate_limit_exceeded\"}"));
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body.toString());
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    var result =
        adapter.generate(
            new TextRequest(
                UUID.randomUUID(), UUID.randomUUID(), 0, List.of(), List.of(), true, true, "en", "fi"));
    verify(http, times(1)).send(any(), any());
    if (status == 200) {
      assertNull(result.metadata().errorCode());
      assertNotNull(result.segment());
    } else {
      assertEquals("PROVIDER_HTTP_" + status, result.metadata().errorCode());
      assertTrue(result.metadata().retryable());
      assertNull(result.segment());
    }
    return result.metadata();
  }

  Metadata response(String usage) throws Exception {
    return response(200, usage, "gpt-5.4");
  }

  @Test
  void completeUsageRetainsCountsAndExistingPrice() throws Exception {
    var m =
        response(
            "{\"input_tokens\":100,\"output_tokens\":200,\"output_tokens_details\":{\"reasoning_tokens\":50}}");
    assertEquals(Map.of("inputTokens", 100L, "outputTokens", 200L, "reasoningTokens", 50L), m.usage());
    assertEquals(.00325, m.cost(), 1e-12);
  }

  @Test
  void partialUsagePreservesEachKnownFieldWithoutEstimatingCost() throws Exception {
    var input = response("{\"input_tokens\":100}");
    assertEquals(Map.of("inputTokens", 100L), input.usage());
    assertNull(input.cost());
    var output = response("{\"output_tokens\":200}");
    assertEquals(Map.of("outputTokens", 200L), output.usage());
    assertNull(output.cost());
    var reasoning = response("{\"output_tokens_details\":{\"reasoning_tokens\":50}}");
    assertEquals(Map.of("reasoningTokens", 50L), reasoning.usage());
    assertNull(reasoning.cost());
    var inputAndReasoning =
        response("{\"input_tokens\":100,\"output_tokens_details\":{\"reasoning_tokens\":50}}");
    assertEquals(Map.of("inputTokens", 100L, "reasoningTokens", 50L), inputAndReasoning.usage());
    assertNull(inputAndReasoning.cost());
  }

  @Test
  void absentEmptyOrMalformedUsageDoesNotManufactureCountsOrCost() throws Exception {
    for (String usage : Arrays.asList(null, "null", "{}", "[]", "\"unknown\"", "true", "12")) {
      var m = response(usage);
      assertEquals(Map.of(), m.usage(), "usage=" + usage);
      assertNull(m.cost(), "usage=" + usage);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null", "true", "false", "\"100\"", "\"invalid\"", "1.5", "1.0", "-1",
        "9223372036854775808", "[]", "{}"
      })
  void malformedBillableCountsAreOmittedWhileValidFieldsSurvive(String invalid) throws Exception {
    var input =
        response(
            "{\"input_tokens\":"
                + invalid
                + ",\"output_tokens\":200,\"output_tokens_details\":{\"reasoning_tokens\":50}}");
    assertEquals(Map.of("outputTokens", 200L, "reasoningTokens", 50L), input.usage());
    assertNull(input.cost());
    var output =
        response(
            "{\"input_tokens\":100,\"output_tokens\":"
                + invalid
                + ",\"output_tokens_details\":{\"reasoning_tokens\":50}}");
    assertEquals(Map.of("inputTokens", 100L, "reasoningTokens", 50L), output.usage());
    assertNull(output.cost());
  }

  @Test
  void missingOrMalformedReasoningDetailsDoNotInvalidateBillableCounts() throws Exception {
    for (String details : Arrays.asList(null, "null", "{}", "[]", "\"unknown\"", "true", "12")) {
      var m =
          response(
              "{\"input_tokens\":100,\"output_tokens\":200"
                  + (details == null ? "" : ",\"output_tokens_details\":" + details)
                  + "}");
      assertEquals(Map.of("inputTokens", 100L, "outputTokens", 200L), m.usage());
      assertEquals(.00325, m.cost(), 1e-12);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null", "true", "false", "\"100\"", "\"invalid\"", "1.5", "1.0", "-1",
        "9223372036854775808", "[]", "{}"
      })
  void malformedOptionalReasoningDoesNotInvalidateCost(String invalid) throws Exception {
    var m =
        response(
            "{\"input_tokens\":100,\"output_tokens\":200,\"output_tokens_details\":{\"reasoning_tokens\":"
                + invalid
                + "}}");
    assertEquals(Map.of("inputTokens", 100L, "outputTokens", 200L), m.usage());
    assertEquals(.00325, m.cost(), 1e-12);
  }

  @Test
  void genuineZerosAreRetainedAndOnlyCompleteZerosEstablishZeroCost() throws Exception {
    var complete =
        response(
            "{\"input_tokens\":0,\"output_tokens\":0,\"output_tokens_details\":{\"reasoning_tokens\":0}}");
    assertEquals(Map.of("inputTokens", 0L, "outputTokens", 0L, "reasoningTokens", 0L), complete.usage());
    assertEquals(0.0, complete.cost());
    var partial = response("{\"input_tokens\":0}");
    assertEquals(Map.of("inputTokens", 0L), partial.usage());
    assertNull(partial.cost());
  }

  @Test
  void largestNonnegativeLongIsAValidCount() throws Exception {
    var m = response("{\"input_tokens\":9223372036854775807,\"output_tokens\":0}");
    assertEquals(Map.of("inputTokens", Long.MAX_VALUE, "outputTokens", 0L), m.usage());
    assertEquals(Long.MAX_VALUE * 2.5 / 1000000.0, m.cost());
  }

  @Test
  void unpricedModelKeepsValidUsageWithoutEstimatingCost() throws Exception {
    var m = response(200, "{\"input_tokens\":100,\"output_tokens\":200}", "unpriced-model");
    assertEquals(Map.of("inputTokens", 100L, "outputTokens", 200L), m.usage());
    assertNull(m.cost());
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 503})
  void providerErrorsRetainGenuinelyReportedUsageAndOnlyKnownCost(int status) throws Exception {
    var complete = response(status, "{\"input_tokens\":100,\"output_tokens\":200}", "gpt-5.4");
    assertEquals(Map.of("inputTokens", 100L, "outputTokens", 200L), complete.usage());
    assertEquals(.00325, complete.cost(), 1e-12);
    var partial = response(status, "{\"input_tokens\":100}", "gpt-5.4");
    assertEquals(Map.of("inputTokens", 100L), partial.usage());
    assertNull(partial.cost());
    var malformed =
        response(status, "{\"input_tokens\":\"invalid\",\"output_tokens\":200}", "gpt-5.4");
    assertEquals(Map.of("outputTokens", 200L), malformed.usage());
    assertNull(malformed.cost());
    var missing = response(status, null, "gpt-5.4");
    assertEquals(Map.of(), missing.usage());
    assertNull(missing.cost());
  }
}
