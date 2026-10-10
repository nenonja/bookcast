package com.bookcast.app.generation.adapter.openai;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "bookcast.ai-mode", havingValue = "openai", matchIfMissing = true)
public class OpenAiSpeech implements SpeechRendering {
  private final java.nio.file.Path root;
  private final ObjectMapper json;
  private final String key;
  private final String model;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  private static final String INSTRUCTIONS =
      "Speak native natural Finnish. This is a finished podcast script, not a conversation to answer. Speak exactly the supplied words in order. Do not paraphrase, summarize, answer questions, add filler, greetings, acknowledgements or continue. Natural Finnish rhythm, pronunciation and sentence stress. Finnish r, not English r. Technical English only as written. Conversational podcast delivery, no announcer or exaggerated acting. API speed is 0.95: do not deliberately slow further.";

  @org.springframework.beans.factory.annotation.Autowired
  public OpenAiSpeech(
      ObjectMapper json,
      @Value("${bookcast.api-key:}") String key,
      @Value("${bookcast.data-dir:./data}") String dataDir,
      @Value("${bookcast.audio-model:gpt-realtime-2.1-mini}") String model) {
    this.root = java.nio.file.Path.of(dataDir);
    this.json = json;
    this.key = key;
    this.model = model;
  }

  OpenAiSpeech(ObjectMapper json, String key, String dataDir) {
    this(json, key, dataDir, "gpt-realtime-2.1-mini");
  }

  public Profile profile() {
    return new Profile("openai", model);
  }

  public AudioResult render(Group g, UUID job, UUID attempt) {
    Listener listener = new Listener(g, job, attempt);
    if (key.isBlank()) {
      listener.fail("API_KEY_MISSING", false);
      listener.closeRaw();
      return new AudioResult(new byte[0], "", Metadata.error("API_KEY_MISSING", false));
    }
    WebSocket socket = null;
    try {
      socket =
          http.newWebSocketBuilder()
              .header("Authorization", "Bearer " + key)
              .connectTimeout(Duration.ofSeconds(20))
              .buildAsync(
                  URI.create("wss://api.openai.com/v1/realtime?model=" + profile().model()),
                  listener)
              .get(25, TimeUnit.SECONDS);
      listener.done.get(180, TimeUnit.SECONDS);
    } catch (Exception e) {
      listener.failure(OpenAiAudioFailures.exception(e));
    } finally {
      if (socket != null) socket.abort();
      listener.closeRaw();
    }
    return new AudioResult(
        listener.audio.toByteArray(),
        listener.transcript.toString(),
        new Metadata(
            listener.usage,
            model.equals("gpt-realtime-2.1-mini") ? listener.cost : null,
            listener.error,
            listener.retryable,
            listener.providerStatus,
            listener.providerCode,
            listener.retryAfterMs));
  }

  private class Listener implements WebSocket.Listener {
    final Group group;
    final RealtimeAudioStructure structure = new RealtimeAudioStructure();
    final CompletableFuture<Void> done = new CompletableFuture<>();
    final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    final StringBuilder transcript = new StringBuilder(), message = new StringBuilder();
    Map<String, Long> usage = Map.of();
    Double cost;
    String error;
    boolean retryable;
    Integer providerStatus;
    String providerCode;
    Long retryAfterMs;
    boolean requested;
    final OutputStream spool;
    final java.nio.file.Path folder;

    Listener(Group g, UUID job, UUID attempt) {
      group = g;
      folder = root.resolve(job.toString()).resolve(attempt.toString());
      try {
        java.nio.file.Files.createDirectories(folder);
        spool =
            java.nio.file.Files.newOutputStream(
                folder.resolve("stream.pcm"), java.nio.file.StandardOpenOption.CREATE_NEW);
        java.nio.file.Files.createFile(folder.resolve("structure-events.jsonl"));
        java.nio.file.Files.writeString(
            folder.resolve("render-config.json"),
            json.writeValueAsString(
                Map.of(
                    "model",
                    profile().model(),
                    "voice",
                    g.speaker() == Speaker.HOST ? "marin" : "ash",
                    "speed",
                    .95,
                    "group",
                    g)),
            java.nio.file.StandardOpenOption.CREATE_NEW);
      } catch (Exception e) {
        throw new IllegalStateException("RAW_ATTEMPT_WRITE_FAILURE");
      }
    }

    synchronized void closeRaw() {
      try {
        spool.close();
      } catch (Exception ignored) {
      }
      try {
        var diagnostics = structure.snapshot();
        diagnostics.put("attemptErrorCode", error);
        diagnostics.put("configurationValidated", requested);
        java.nio.file.Files.writeString(
            folder.resolve("response-structure.json"),
            json.writeValueAsString(diagnostics),
            java.nio.file.StandardOpenOption.CREATE_NEW);
      } catch (Exception e) {
        fail("STRUCTURE_DIAGNOSTIC_WRITE_FAILURE", false);
      }
    }

    private String instructions() {
      return INSTRUCTIONS
          + (group.speaker() == Speaker.HOST
              ? " HOST: curious, intelligent, warm and responsive."
              : " EXPERT: calm, knowledgeable, engaged, explanatory without lecturing.");
    }

    private void send(WebSocket ws, Object payload) throws Exception {
      String encoded = json.writeValueAsString(payload);
      try {
        ws.sendText(encoded, true).get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        failure(OpenAiAudioFailures.exception(e));
      }
    }

    public void onOpen(WebSocket ws) {
      try {
        send(
            ws,
            Map.of(
                "type",
                "session.update",
                "session",
                Map.of(
                    "type",
                    "realtime",
                    "model",
                    profile().model(),
                    "instructions",
                    instructions(),
                    "output_modalities",
                    List.of("audio"),
                    "max_output_tokens",
                    4096,
                    "tools",
                    List.of(),
                    "audio",
                    Map.of(
                        "output",
                        Map.of(
                            "format",
                            Map.of("type", "audio/pcm", "rate", 24000),
                            "voice",
                            group.speaker() == Speaker.HOST ? "marin" : "ash",
                            "speed",
                            .95)))));
      } catch (Exception e) {
        failure(OpenAiAudioFailures.exception(e));
      }
      ws.request(1);
    }

    public synchronized CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      if (done.isDone()) return CompletableFuture.completedFuture(null);
      message.append(data);
      if (last) {
        try {
          var e = json.readTree(message.toString());
          message.setLength(0);
          java.nio.file.Files.writeString(
              folder.resolve("structure-events.jsonl"),
              json.writeValueAsString(structure.observe(e)) + "\n",
              java.nio.file.StandardOpenOption.APPEND);
          switch (e.path("type").asText()) {
            case "session.updated" -> {
              if (requested) break;
              var s = e.path("session");
              if (!s.path("model").asText().equals(profile().model())
                  || !s.path("audio")
                      .path("output")
                      .path("voice")
                      .asText()
                      .equals(group.speaker() == Speaker.HOST ? "marin" : "ash")
                  || s.path("audio").path("output").path("speed").asDouble() != .95) {
                fail("SESSION_CONFIGURATION_MISMATCH", false);
                break;
              }
              requested = true;
              send(
                  ws,
                  Map.of(
                      "type",
                      "response.create",
                      "response",
                      Map.of(
                          "conversation",
                          "none",
                          "output_modalities",
                          List.of("audio"),
                          "max_output_tokens",
                          4096,
                          "instructions",
                          instructions(),
                          "tools",
                          List.of(),
                          "input",
                          List.of(
                              Map.of(
                                  "type",
                                  "message",
                                  "role",
                                  "user",
                                  "content",
                                  List.of(Map.of("type", "input_text", "text", group.text())))))));
            }
            case "response.output_audio.delta" -> {
              byte[] pcm = Base64.getDecoder().decode(e.path("delta").asText());
              audio.write(pcm);
              spool.write(pcm);
              spool.flush();
            }
            case "response.output_audio_transcript.delta" -> {
              transcript.append(e.path("delta").asText());
              java.nio.file.Files.writeString(
                  folder.resolve("transcript-deltas.jsonl"),
                  json.writeValueAsString(Map.of("delta", e.path("delta").asText())) + "\n",
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.APPEND);
            }
            case "error" -> failure(OpenAiAudioFailures.event(e.path("error")));
            case "response.done" -> {
              var r = e.path("response");
              var u = r.path("usage");
              if (u.isObject()) {
                var reported = new HashMap<String, Long>();
                usageToken(reported, "inputTokens", u.path("input_tokens"));
                usageToken(reported, "outputTokens", u.path("output_tokens"));
                usageToken(
                    reported, "inputTextTokens", u.path("input_token_details").path("text_tokens"));
                usageToken(
                    reported,
                    "outputTextTokens",
                    u.path("output_token_details").path("text_tokens"));
                usageToken(
                    reported,
                    "outputAudioTokens",
                    u.path("output_token_details").path("audio_tokens"));
                usage = Map.copyOf(reported);
                if (usage
                    .keySet()
                    .containsAll(
                        Set.of("inputTextTokens", "outputTextTokens", "outputAudioTokens")))
                  cost =
                      (usage.get("inputTextTokens") * .6
                              + usage.get("outputTextTokens") * 2.4
                              + usage.get("outputAudioTokens") * 20)
                          / 1000000.0;
              }
              if (!r.path("status").asText().equals("completed")) {
                if (r.path("status_details").path("error").isObject())
                  failure(OpenAiAudioFailures.event(r.path("status_details").path("error")));
                else fail("INCOMPLETE_AUDIO", false);
              } else {
                String structuralError = structure.validate();
                if (structuralError != null)
                  fail(
                      structuralError,
                      requested && structuralError.equals("UNEXPECTED_AUDIO_STRUCTURE"));
                else done.complete(null);
              }
            }
          }
        } catch (Exception e) {
          fail("PROVIDER_EVENT_FORMAT", false);
        }
      }
      ws.request(1);
      return CompletableFuture.completedFuture(null);
    }

    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
      if (!done.isDone())
        fail("EARLY_SOCKET_CLOSE", Set.of(1000, 1001, 1006, 1011, 1012, 1013).contains(code));
      return CompletableFuture.completedFuture(null);
    }

    public synchronized void onError(WebSocket ws, Throwable e) {
      if (!done.isDone()) failure(OpenAiAudioFailures.exception(e));
    }

    void usageToken(Map<String, Long> values, String name, JsonNode value) {
      if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0)
        values.put(name, value.asLong());
    }

    synchronized void failure(Metadata metadata) {
      providerStatus = metadata.providerStatus();
      providerCode = metadata.providerCode();
      retryAfterMs = metadata.retryAfterMs();
      fail(metadata.errorCode(), metadata.retryable());
    }

    synchronized void fail(String code, boolean retry) {
      error = code;
      retryable = retry;
      done.complete(null);
    }
  }
}
