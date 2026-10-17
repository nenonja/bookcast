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

/**
 * Renders one prepared same-speaker group through an isolated OpenAI Realtime WebSocket session.
 *
 * <p>Requests Finnish delivery of the supplied script text, with HOST voice {@code marin}, EXPERT
 * voice {@code ash}, speed {@code 0.95} and 24,000 Hz PCM output. Each call configures a new session
 * and requests a response outside conversation history, with tools disabled. These instructions
 * request faithful speech; they do not prove that every supplied word was spoken.
 *
 * <p>Owns streaming transport, raw attempt files, provider usage and structural validation through
 * {@link RealtimeAudioStructure}. Audio and transcript deltas are preserved even when an attempt
 * fails. The application owns retries, checkpoint acceptance, waveform validation and assembly;
 * this adapter neither resumes an old attempt nor repairs, trims or rerenders its output.
 */
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

  /**
   * Opens a fresh attempt, collects its speech stream and returns the received bytes and transcript
   * together with success or failure metadata.
   *
   * <p>Creates raw files under the job/attempt IDs before checking credentials. Initial PCM,
   * configuration and structural-journal files use exclusive creation so an existing attempt is not
   * overwritten. A blank API key returns empty audio/transcript and {@code API_KEY_MISSING} without
   * connecting, after attempting to write final diagnostics. The connection future is awaited for
   * up to 25 seconds, followed by up to 180 seconds for listener completion. An acquired socket is
   * aborted and raw-file finalization is attempted before returning.
   *
   * <p>The listener supplies protocol and provider-error classifications; connection/wait exceptions
   * use {@link OpenAiAudioFailures}. A result with an error can still contain partial audio,
   * transcript and usage. A result without an error has passed the structural checks, but still
   * requires the application's waveform validation before acceptance.
   *
   * <p>Estimated cost is in USD, only for {@code gpt-realtime-2.1-mini}, and requires valid input-text,
   * output-text and output-audio token counts from {@code response.done}. Other valid usage fields
   * are retained independently. Missing counts are not treated as zero, and usage reported on an
   * incomplete response is retained.
   *
   * @param g non-null group already partitioned by the caller; its speaker selects the voice and
   *     its text is supplied as the script to speak
   * @param job non-null generation ID used to locate the attempt directory
   * @param attempt non-null fresh attempt ID whose initial raw files do not yet exist
   * @return received PCM bytes requested at 24,000 Hz, concatenated transcript deltas and metadata;
   *     bytes/text may be empty or partial, and unknown cost is {@code null}
   * @throws IllegalStateException with {@code RAW_ATTEMPT_WRITE_FAILURE} if initial attempt files
   *     cannot be created; initialization happens before transport exception handling
   */
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

  /**
   * Holds one attempt's streaming state, raw-file spool and protocol evidence.
   *
   * <p>The first matching {@code session.updated} acknowledgement triggers a single response request;
   * model, voice and speed must match before that request is sent. Fragmented WebSocket text is
   * assembled into JSON events, whose structural summaries are journaled separately from private
   * script/transcript payloads. Raw PCM is also accumulated in memory for the returned result.
   *
   * <p>The completion future is completed normally for both success and failure: the error fields
   * distinguish outcomes. Text callbacks ignore further fragments after completion. Completion does
   * not freeze failure metadata; later failure/finalization calls can replace an earlier error.
   */
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

    /**
     * Closes the PCM spool and creates the final structural sidecar without replacing an existing
     * sidecar. Spool-close exceptions are ignored; a sidecar failure records non-retryable
     * {@code STRUCTURE_DIAGNOSTIC_WRITE_FAILURE}, even if another error was already recorded.
     *
     * <p>This operation does not validate the response or remove partial files. It is not idempotent:
     * exclusive sidecar creation causes a second invocation to record a write failure.
     */
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

    /**
     * Serializes a command and waits up to ten seconds for its WebSocket send to complete.
     * Send/wait exceptions are classified into listener failure state and are not rethrown;
     * serialization exceptions propagate to the calling callback's handler.
     */
    private void send(WebSocket ws, Object payload) throws Exception {
      String encoded = json.writeValueAsString(payload);
      try {
        ws.sendText(encoded, true).get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        failure(OpenAiAudioFailures.exception(e));
      }
    }

    /**
     * Sends the requested audio-session configuration, then requests the first inbound callback.
     * Script submission is deferred until a matching {@code session.updated} acknowledgement.
     */
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

    /**
     * Processes one WebSocket text fragment, decoding an event only when its last fragment arrives.
     *
     * <p>Before handling an event, appends a payload-free summary to the structural journal. Audio
     * deltas are decoded, buffered and flushed to the PCM spool; transcript deltas are concatenated
     * and appended to a separate private journal. Final transcript fields are not substituted for
     * the accumulated deltas, and no lexical comparison with the script is performed here.
     *
     * <p>On {@code response.done}, valid usage is recorded before checking status. A non-completed
     * response uses its structured provider error if present, otherwise non-retryable
     * {@code INCOMPLETE_AUDIO}. A completed response must pass structural validation. Only
     * {@code UNEXPECTED_AUDIO_STRUCTURE} after configuration acknowledgement is marked retryable;
     * unsupported schemas stop without a retry hint. Event decoding and artifact-writing exceptions
     * share the non-retryable {@code PROVIDER_EVENT_FORMAT} classification.
     *
     * @param ws current provider socket, used to send the script request and request further input
     * @param data next fragment of the current JSON event
     * @param last whether this fragment completes the event message
     * @return an already-completed callback stage; attempt completion is signaled separately through
     *     {@code done}, and callbacks arriving after that completion are ignored
     */
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

    /**
     * Records {@code EARLY_SOCKET_CLOSE} only if the attempt has not completed. Codes 1000, 1001,
     * 1006, 1011, 1012 and 1013 are marked retryable; other close codes are not. The provider's
     * human-readable close reason is neither retained nor used for classification.
     */
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
      if (!done.isDone())
        fail("EARLY_SOCKET_CLOSE", Set.of(1000, 1001, 1006, 1011, 1012, 1013).contains(code));
      return CompletableFuture.completedFuture(null);
    }

    public synchronized void onError(WebSocket ws, Throwable e) {
      if (!done.isDone()) failure(OpenAiAudioFailures.exception(e));
    }

    /**
     * Copies a reported nonnegative JSON integer only if it fits in a {@code long}. Absent,
     * malformed and out-of-range counts remain unknown; a genuinely reported zero is retained.
     */
    void usageToken(Map<String, Long> values, String name, JsonNode value) {
      if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0)
        values.put(name, value.asLong());
    }

    /**
     * Replaces provider status/code/retry hints and records the failure without discarding any
     * previously collected audio, transcript or usage.
     */
    synchronized void failure(Metadata metadata) {
      providerStatus = metadata.providerStatus();
      providerCode = metadata.providerCode();
      retryAfterMs = metadata.retryAfterMs();
      fail(metadata.errorCode(), metadata.retryable());
    }

    /**
     * Replaces the current error classification and wakes the rendering caller by completing
     * {@code done} normally. This is not a first-failure latch: later calls can replace the code and
     * retry flag even after completion, and provider-specific hints are left unchanged.
     */
    synchronized void fail(String code, boolean retry) {
      error = code;
      retryable = retry;
      done.complete(null);
    }
  }
}
