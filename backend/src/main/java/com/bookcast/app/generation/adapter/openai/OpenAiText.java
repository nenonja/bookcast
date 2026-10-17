package com.bookcast.app.generation.adapter.openai;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.Segment;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapts Bookcast's text-generation port to synchronous OpenAI Responses requests.
 *
 * <p>Translates the requested source blocks, neighboring turns and language identifiers into provider
 * input, using the bundled {@code dialogue-v1.txt} instructions and a strict JSON output schema.
 * The prompt is loaded at construction; an unavailable or unreadable resource fails initialization
 * with {@code PROMPT_RESOURCE_MISSING}. Construction itself makes no provider call.
 *
 * <p>The returned segment is a candidate. Deserialization applies the domain records' constructor
 * checks, but source coverage, expected ordering and dialogue normalization are the application's
 * responsibility. Neither the provider's schema nor successful parsing establishes source fidelity.
 *
 * <p>Owns provider translation, candidate-file preservation and attempt usage/error metadata. It does
 * not accept checkpoints or retry calls; the application decides whether to retry a failed unit and
 * supplies a new attempt identity. Raw provider error messages are not included in returned metadata.
 */
@Component
@ConditionalOnProperty(name = "bookcast.ai-mode", havingValue = "openai", matchIfMissing = true)
public class OpenAiText implements TextGeneration {
  private final java.nio.file.Path root;
  private final ObjectMapper json;
  private final String key;
  private final String model;
  private final String prompt;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

  @org.springframework.beans.factory.annotation.Autowired
  public OpenAiText(
      ObjectMapper json,
      @Value("${bookcast.api-key:}") String key,
      @Value("${bookcast.data-dir:./data}") String dataDir,
      @Value("${bookcast.dialogue-model:gpt-5.4}") String model) {
    this.root = java.nio.file.Path.of(dataDir);
    this.json = json;
    this.key = key;
    this.model = model;
    try {
      prompt =
          new String(
              Objects.requireNonNull(getClass().getResourceAsStream("/prompts/dialogue-v1.txt"))
                  .readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("PROMPT_RESOURCE_MISSING");
    }
  }

  OpenAiText(ObjectMapper json, String key, String dataDir) {
    this(json, key, dataDir, "gpt-5.4");
  }

  public Profile profile() {
    return new Profile("openai", model);
  }

  /**
   * Requests a structured dialogue candidate and retains any valid reported usage, including on
   * HTTP errors, incomplete responses and subsequent candidate-processing failures.
   *
   * <p>A blank API key returns {@code API_KEY_MISSING} without connecting. The HTTP request has a
   * 180-second timeout. For an HTTP 200 response marked {@code completed}, all {@code output_text}
   * content is concatenated and written to {@code dialogue-candidate.json} under the generation and
   * attempt IDs before deserialization. Creation refuses to replace an existing candidate, and a
   * candidate that fails parsing remains available for inspection. Non-200 and incomplete responses
   * do not write a candidate.
   *
   * <p>Usage contains only reported nonnegative integer counts that fit in a {@code long}; missing
   * or malformed fields are omitted independently, while genuine zeros are retained. Estimated cost
   * is in USD and is available only for {@code gpt-5.4} when both input and output token counts are
   * valid. Reasoning-token usage is optional and is not charged separately by this calculation.
   *
   * <p>HTTP 429 and 5xx responses are retryable unless structured errors identify quota/billing or
   * an invalid request, authentication or permission error type. HTTP metadata retains the status,
   * safe provider code and parsed Retry-After delay in milliseconds. Transport failures use
   * {@link OpenAiAudioFailures}; interruption restores the interrupt flag. Request construction,
   * parsing and local persistence failures are non-retryable {@code PROVIDER_OR_SCHEMA_FAILURE}
   * results. These classifications do not initiate a retry.
   *
   * @param r non-null request with source/context data and non-null generation/attempt IDs; the
   *     attempt must have an unused candidate-file path
   * @return parsed candidate and metadata; a JSON {@code null} candidate can yield a {@code null}
   *     segment without an adapter error, which the application must reject. Operational failures
   *     return a {@code null} segment with error metadata. Unknown cost is {@code null}, and
   *     unavailable usage fields are absent
   */
  public TextResult generate(TextRequest r) {
    if (key.isBlank()) return new TextResult(null, Metadata.error("API_KEY_MISSING", false));
    Metadata metadata = Metadata.error("PROVIDER_TRANSPORT_FAILURE", true);
    try {
      var source = json.createObjectNode();
      source.put("order", r.order());
      source.put("isFirst", r.first());
      source.put("isLast", r.last());
      source.put("sourceLanguage", r.sourceLanguage());
      source.put("outputLanguage", r.outputLanguage());
      source.set("sourceBlocks", json.valueToTree(r.blocks()));
      source.set("continuity", json.valueToTree(r.previousTurns()));
      String schema =
          """
  {"type":"object","additionalProperties":false,"required":["order","coveredSourceBlockIds","dialogueTurns"],"properties":{"order":{"type":"integer"},"coveredSourceBlockIds":{"type":"array","items":{"type":"string"}},"dialogueTurns":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["speakerRole","text"],"properties":{"speakerRole":{"type":"string","enum":["HOST","EXPERT"]},"text":{"type":"string"}}}}}}
  """;
      var body = json.createObjectNode();
      body.put("model", profile().model());
      body.put("store", false);
      body.put("max_output_tokens", 8000);
      body.set("reasoning", json.valueToTree(Map.of("effort", "low")));
      body.put("instructions", prompt);
      body.put("input", source.toString());
      body.set(
          "text",
          json.valueToTree(
              Map.of(
                  "format",
                  Map.of(
                      "type",
                      "json_schema",
                      "name",
                      "script_segment",
                      "strict",
                      true,
                      "schema",
                      json.readTree(schema)))));
      var request =
          HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
              .timeout(Duration.ofSeconds(180))
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
              .build();
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new TextResult(null, Metadata.error("PROCESS_INTERRUPTED", false));
      } catch (java.io.IOException e) {
        var failure = OpenAiAudioFailures.exception(e);
        return new TextResult(null, failure);
      }
      JsonNode d;
      try {
        d = json.readTree(response.body());
        if (d == null) d = json.createObjectNode();
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        if (response.statusCode() == 200)
          return new TextResult(null, Metadata.error("PROVIDER_OR_SCHEMA_FAILURE", false));
        // HTML/empty transient error responses still have a useful structured HTTP status.
        d = json.createObjectNode();
      }
      var usage = d.path("usage");
      var reported = new HashMap<String, Long>();
      if (usage.isObject()) {
        usageToken(reported, "inputTokens", usage.path("input_tokens"));
        usageToken(reported, "outputTokens", usage.path("output_tokens"));
        usageToken(
            reported, "reasoningTokens", usage.path("output_tokens_details").path("reasoning_tokens"));
      }
      metadata =
          new Metadata(
              reported,
              !model.equals("gpt-5.4")
                      || !reported.keySet().containsAll(Set.of("inputTokens", "outputTokens"))
                  ? null
                  : (reported.get("inputTokens") * 2.5 + reported.get("outputTokens") * 15)
                      / 1000000.0,
              null,
              false);
      if (response.statusCode() != 200) {
        int status = response.statusCode();
        var providerError = OpenAiAudioFailures.event(d.path("error"));
        boolean quota = "PROVIDER_QUOTA".equals(providerError.errorCode());
        String type = d.path("error").path("type").asText();
        boolean invalid =
            Set.of("invalid_request_error", "authentication_error", "permission_error")
                .contains(type);
        Long retryAfter =
            response.headers() == null
                ? null
                : OpenAiAudioFailures.retryAfter(
                    response.headers().firstValue("Retry-After").orElse(null),
                    java.time.Instant.now());
        return new TextResult(
            null,
            new Metadata(
                metadata.usage(),
                metadata.cost(),
                quota ? "PROVIDER_QUOTA" : "PROVIDER_HTTP_" + status,
                !quota && !invalid && (status == 429 || status >= 500 && status <= 599),
                status,
                providerError.providerCode(),
                retryAfter));
      }
      if (!d.path("status").asText().equals("completed"))
        return new TextResult(
            null, new Metadata(metadata.usage(), metadata.cost(), "INCOMPLETE_DIALOGUE", false));
      StringBuilder output = new StringBuilder();
      for (var message : d.path("output"))
        for (var content : message.path("content"))
          if (content.path("type").asText().equals("output_text"))
            output.append(content.path("text").asText());
      var folder = root.resolve(r.generationId().toString()).resolve(r.attemptId().toString());
      java.nio.file.Files.createDirectories(folder);
      java.nio.file.Files.writeString(
          folder.resolve("dialogue-candidate.json"),
          output.toString(),
          java.nio.file.StandardOpenOption.CREATE_NEW);
      return new TextResult(json.readValue(output.toString(), Segment.class), metadata);
    } catch (Exception e) {
      // Request construction, parsing and local persistence failures must not spend retry calls.
      return new TextResult(
          null,
          new Metadata(metadata.usage(), metadata.cost(), "PROVIDER_OR_SCHEMA_FAILURE", false));
    }
  }

  /**
   * Retains a reported nonnegative JSON integer only when it fits in a {@code long}.
   * Missing, null and malformed values remain absent from the usage map; numeric zero is retained.
   */
  private static void usageToken(Map<String, Long> values, String name, JsonNode value) {
    if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0)
      values.put(name, value.asLong());
  }
}
