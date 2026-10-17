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
      metadata =
          new Metadata(
              !usage.isObject()
                  ? Map.of()
                  : Map.of(
                      "inputTokens",
                      usage.path("input_tokens").asLong(),
                      "outputTokens",
                      usage.path("output_tokens").asLong(),
                      "reasoningTokens",
                      usage.path("output_tokens_details").path("reasoning_tokens").asLong()),
              !usage.isObject() || !model.equals("gpt-5.4")
                  ? null
                  : (usage.path("input_tokens").asLong() * 2.5
                          + usage.path("output_tokens").asLong() * 15)
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
}
