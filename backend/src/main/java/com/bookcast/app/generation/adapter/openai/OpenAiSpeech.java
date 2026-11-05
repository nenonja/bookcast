package com.bookcast.app.generation.adapter.openai;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import static java.nio.file.Files.*;
/**
 * Renders one prepared same-speaker group through an isolated OpenAI Realtime WebSocket session.
 *
 * <p>Requests Finnish delivery of the supplied script text with configured voices and speed
 * (defaults: HOST {@code marin}, EXPERT {@code ash}, speed {@code 0.95}) and 24,000 Hz PCM output.
 * Each call configures a new session
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
@EnableConfigurationProperties(OpenAiSpeechSettings.class)
public class OpenAiSpeech implements SpeechRendering {
  /**
   * Per-attempt budgets; character limits count UTF-16 code units, audio limits count PCM bytes.
   * Saved attempts peaked at 4.57 MB PCM, 1,392 transcript characters and 614 structural events.
   * Defaults allow roughly 350 seconds of 24 kHz mono PCM, 44 seconds in one delta and substantial
   * transcript/event headroom. JSON includes Base64 expansion; timeouts and provider token limits
   * are independent of these memory budgets. The local Jackson parser additionally caps depth at
   * 64, tokens at 16,384 and field names at 256 characters, bounding tree and name-cache growth.
   * Package-private overrides keep offline tests small; the shared mapper is not reconfigured.
   */
  record Limits(int messageChars, int audioDeltaBytes, int pcmBytes, int transcriptChars,
                int events, int structuralEntries) {
    static final Limits DEFAULT = new Limits(
        4 * 1024 * 1024, 2 * 1024 * 1024, 16 * 1024 * 1024, 64 * 1024,
        RealtimeAudioStructure.MAX_EVENTS, RealtimeAudioStructure.MAX_STRUCTURAL_ENTRIES);

    Limits {
      if (messageChars < 1 || audioDeltaBytes < 1 || pcmBytes < 1 || transcriptChars < 1
          || events < 1 || structuralEntries < 1)
        throw new IllegalArgumentException("INVALID_REALTIME_LIMITS");
    }
  }

  private static final int MAX_JSON_DEPTH = 64;
  private static final int MAX_JSON_TOKENS = 16_384;
  private static final int MAX_JSON_NAME_CHARS = 256;
  private final java.nio.file.Path root;
  private final ObjectMapper json;
  private final JsonFactory eventJson;
  private final Limits limits;
  private final String key;
  private final String model;
  private final OpenAiSpeechSettings settings;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  private static final String INSTRUCTIONS =
      "Speak native natural Finnish. This is a finished podcast script, not a conversation to answer. Speak exactly the supplied words in order. Do not paraphrase, summarize, answer questions, add filler, greetings, acknowledgements or continue. Natural Finnish rhythm, pronunciation and sentence stress. Finnish r, not English r. Technical English only as written. Conversational podcast delivery, no announcer or exaggerated acting.";

  @org.springframework.beans.factory.annotation.Autowired
  public OpenAiSpeech(
      ObjectMapper json,
      @Value("${bookcast.api-key:}") String key,
      @Value("${bookcast.data-dir:./data}") String dataDir,
      @Value("${bookcast.audio-model:gpt-realtime-2.1-mini}") String model,
      OpenAiSpeechSettings settings) {
    this(json, key, dataDir, model, settings, Limits.DEFAULT);
  }

  public OpenAiSpeech(ObjectMapper json, String key, String dataDir, String model) {
    this(json, key, dataDir, model, OpenAiSpeechSettings.DEFAULT);
  }

  OpenAiSpeech(ObjectMapper json, String key, String dataDir, String model, Limits limits) {
    this(json, key, dataDir, model, OpenAiSpeechSettings.DEFAULT, limits);
  }

  OpenAiSpeech(ObjectMapper json, String key, String dataDir, String model,
      OpenAiSpeechSettings settings, Limits limits) {
    if (model == null || model.isBlank()) throw new IllegalArgumentException("INVALID_AUDIO_MODEL");
    this.root = java.nio.file.Path.of(dataDir);
    this.json = json;
    this.key = key;
    this.model = model;
    this.settings = Objects.requireNonNull(settings);
    this.limits = limits;
    eventJson = json.getFactory().copy();
    eventJson.setStreamReadConstraints(StreamReadConstraints.builder()
        .maxDocumentLength(limits.messageChars()).maxStringLength(limits.messageChars())
        .maxNestingDepth(MAX_JSON_DEPTH).maxTokenCount(MAX_JSON_TOKENS)
        .maxNameLength(MAX_JSON_NAME_CHARS).build());
  }

  OpenAiSpeech(ObjectMapper json, String key, String dataDir) {
    this(json, key, dataDir, "gpt-realtime-2.1-mini");
  }

  public Profile profile() {
    return new Profile("openai", model);
  }

  /**
   * Creates an exclusive, immutable speech-settings.json per generation, or rejects a mismatch with
   * SPEECH_CONFIGURATION_CHANGED before any paid work. Legacy generations can adopt the historical
   * default voices/speed only after all existing attempt configurations agree with model, selected
   * voice and speed. Verified legacy differences use SPEECH_CONFIGURATION_MISMATCH; missing or
   * malformed evidence is rejected as SPEECH_CONFIGURATION_UNVERIFIED.
   * No existing attempt/configuration file is rewritten. Synchronization matches the supported
   * single-backend deployment and prevents start/worker races while publishing the small snapshot.
   *
   * @param generationId non-null ID whose private generation directory holds the snapshot
   * @throws IllegalStateException if settings changed, legacy evidence cannot establish compatibility,
   *     or the snapshot cannot be read/created; no provider request has been made
   */
  @Override
  public synchronized void prepareGeneration(UUID generationId) {
    Path folder = root.resolve(generationId.toString());
    Path snapshot = folder.resolve("speech-settings.json");
    JsonNode expected = json.valueToTree(Map.of(
        "model", model, "hostVoice", settings.hostVoice(),
        "expertVoice", settings.expertVoice(), "speed", settings.speed()));
    try {
      if (exists(snapshot)) {
        JsonNode saved = json.readTree(readString(snapshot));
        if (saved == null || !saved.isObject())
          throw new IllegalStateException("SPEECH_CONFIGURATION_UNVERIFIED");
        if (!expected.equals(saved))
          throw new IllegalStateException("SPEECH_CONFIGURATION_CHANGED");
        return;
      }
      if (exists(folder)) verifyLegacySettings(folder);
      createDirectories(folder);
      writeString(snapshot, json.writeValueAsString(expected),
          java.nio.file.StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new IllegalStateException("SPEECH_CONFIGURATION_UNVERIFIED");
    }
  }

  private void verifyLegacySettings(Path folder) throws IOException {
    boolean mismatch = false;
    try (var paths = list(folder)) {
      for (Path attempt : paths.filter(java.nio.file.Files::isDirectory).toList()) {
        Path config = attempt.resolve("render-config.json");
        if (!exists(config)) {
          if (exists(attempt.resolve("stream.pcm")) || exists(attempt.resolve("raw.pcm")))
            throw new IllegalStateException("SPEECH_CONFIGURATION_UNVERIFIED");
          continue;
        }
        JsonNode saved = json.readTree(readString(config));
        if (saved == null || !saved.isObject())
          throw new IllegalStateException("SPEECH_CONFIGURATION_UNVERIFIED");
        String role = saved.path("group").path("speaker").asText();
        if (!Set.of("HOST", "EXPERT").contains(role) || !saved.path("speed").isNumber()
            || !Double.isFinite(saved.path("speed").asDouble())
            || !saved.path("voice").isTextual() || !saved.path("model").isTextual())
          throw new IllegalStateException("SPEECH_CONFIGURATION_UNVERIFIED");
        if (!model.equals(saved.path("model").asText())
            || !settings.voice(Speaker.valueOf(role)).equals(saved.path("voice").asText())
            || saved.path("speed").asDouble() != settings.speed())
          mismatch = true;
      }
    }
    if (mismatch) throw new IllegalStateException("SPEECH_CONFIGURATION_MISMATCH");
    if (!settings.equals(OpenAiSpeechSettings.DEFAULT))
      throw new IllegalStateException("SPEECH_CONFIGURATION_UNVERIFIED");
  }

  /**
   * Opens a fresh attempt, collects its speech stream and returns the received bytes and transcript
   * together with success or failure metadata.
   *
   * <p>Creates raw files under the job/attempt IDs before checking credentials. Initial PCM,
   * configuration and structural-journal files use exclusive creation so an existing attempt is not
   * overwritten. A blank API key returns empty audio/transcript and {@code API_KEY_MISSING} without
   * connecting, after attempting to write final diagnostics. The connection future is awaited for
   * up to 25 seconds, followed by up to 180 seconds for listener completion. Acquired sockets are
   * aborted and raw files finalized before returning; sockets delivered after termination are
   * aborted without issuing commands. An unfinished connection future is cancelled on exit.
   *
   * <p>The listener supplies protocol and provider-error classifications; connection/wait exceptions
   * use {@link OpenAiAudioFailures}. A result with an error can still contain partial audio,
   * transcript and usage. A result without an error has passed the structural checks, but still
   * requires the application's waveform validation before acceptance. Exceeding a local stream or
   * parser budget stops input with non-retryable {@code PROVIDER_RESOURCE_LIMIT}; previously collected
   * PCM, transcript and known accounting remain available. Oversized data is never truncated into
   * a successful result.
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
   *     cannot be created, or a speech-configuration error from {@link #prepareGeneration(UUID)};
   *     these checks happen before transport exception handling
   */
  public AudioResult render(Group g, UUID job, UUID attempt) {
    prepareGeneration(job);
    Listener listener = new Listener(g, job, attempt);
    CompletableFuture<WebSocket> connection = null;
    try {
      if (key.isBlank()) listener.fail("API_KEY_MISSING", false);
      else {
        connection =
            http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + key)
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(
                    URI.create("wss://api.openai.com/v1/realtime?model=" + profile().model()),
                    listener);
        connection.thenAccept(listener::connected);
        connection.get(25, TimeUnit.SECONDS);
        listener.done.get(180, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      listener.failure(OpenAiAudioFailures.exception(e));
    } finally {
      try {
        listener.closeRaw();
      } finally {
        if (connection != null) connection.cancel(true);
      }
    }
    return listener.result();
  }

  private enum Phase { CONNECTING, CONFIGURING, CONFIGURED, REQUESTED, COMPLETED, CLOSED }

  /**
   * Holds one attempt's streaming state, raw-file spool and protocol evidence.
   *
   * <p>The first matching {@code session.updated} acknowledgement triggers a single response request;
   * model, voice and speed must match before that request is sent. Fragmented WebSocket text is
   * assembled into JSON events, whose structural summaries are journaled separately from private
   * script/transcript payloads. Raw PCM is also accumulated in memory for the returned result.
   *
   * <p>The monitor protects phase transitions, the first primary failure and the final result
   * snapshot. The completion future is a terminal latch, completed normally for both outcomes.
   * Phase retains protocol progress after failure for diagnostics; {@code done} stops commands and
   * callbacks, and {@code CLOSED} marks resource finalization.
   * Completed audio requires verified configuration and a successful response submission. Terminal
   * callbacks cannot issue commands or replace the primary failure. Finalization can reject an
   * otherwise successful attempt; its safe error codes are retained separately in diagnostics.
   */
  private class Listener implements WebSocket.Listener {
    final Group group;
    final RealtimeAudioStructure structure =
        new RealtimeAudioStructure(limits.events(), limits.structuralEntries());
    final CompletableFuture<Void> done = new CompletableFuture<>();
    final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    final StringBuilder transcript = new StringBuilder(), message = new StringBuilder();
    Map<String, Long> usage = Map.of();
    Double cost;
    Metadata outcome = new Metadata(Map.of(), null, null, false);
    String resourceLimit;
    Phase phase = Phase.CONNECTING;
    final List<String> finalizationErrors = new ArrayList<>();
    WebSocket socket;
    final OutputStream spool;
    final java.nio.file.Path folder;

    Listener(Group g, UUID job, UUID attempt) {
      group = g;
      folder = root.resolve(job.toString()).resolve(attempt.toString());
      OutputStream opened = null;
      try {
        createDirectories(folder);
        opened =
            newOutputStream(
                folder.resolve("stream.pcm"), java.nio.file.StandardOpenOption.CREATE_NEW);
        createFile(folder.resolve("structure-events.jsonl"));
        writeString(
            folder.resolve("render-config.json"),
            json.writeValueAsString(
                Map.of(
                    "model",
                    profile().model(),
                    "voice",
                    settings.voice(g.speaker()),
                    "speed",
                    settings.speed(),
                    "group",
                    g)),
            java.nio.file.StandardOpenOption.CREATE_NEW);
      } catch (Exception e) {
        var failure = new IllegalStateException("RAW_ATTEMPT_WRITE_FAILURE");
        if (opened != null) {
          try {
            opened.close();
          } catch (Exception closeError) {
            failure.addSuppressed(new IllegalStateException("RAW_ATTEMPT_CLOSE_FAILURE"));
          }
        }
        throw failure;
      }
      spool = opened;
    }

    /**
     * Aborts the owned socket, closes the spool and creates the structural sidecar exactly once.
     * Finalization failures reject otherwise successful work but preserve an earlier primary failure
     * and its provider metadata. Safe finalization error codes are included in the sidecar, or
     * appended to the existing structural journal if sidecar creation fails.
     *
     * <p>No existing sidecar or partial artifact is replaced or removed. If both diagnostic writes
     * fail, secondary errors cannot be persisted; the primary failure is still returned.
     */
    synchronized void closeRaw() {
      if (phase == Phase.CLOSED) return;
      if (!done.isDone()) fail("INCOMPLETE_AUDIO", false);
      try {
        if (socket != null) socket.abort();
      } catch (Exception e) {
        finalizationFailure("SOCKET_ABORT_FAILURE");
      }
      try {
        spool.close();
      } catch (Exception e) {
        finalizationFailure("RAW_ATTEMPT_CLOSE_FAILURE");
      }
      try {
        var diagnostics = structure.snapshot();
        diagnostics.put("attemptErrorCode", outcome.errorCode());
        diagnostics.put("configurationValidated",
            phase == Phase.CONFIGURED || phase == Phase.REQUESTED || phase == Phase.COMPLETED);
        diagnostics.put("responseSubmitted", phase == Phase.REQUESTED || phase == Phase.COMPLETED);
        diagnostics.put("finalizationErrors", List.copyOf(finalizationErrors));
        diagnostics.put("resourceLimit", resourceLimit);
        writeString(
            folder.resolve("response-structure.json"),
            json.writeValueAsString(diagnostics),
            java.nio.file.StandardOpenOption.CREATE_NEW);
      } catch (Exception e) {
        finalizationFailure("STRUCTURE_DIAGNOSTIC_WRITE_FAILURE");
        try {
          writeString(
              folder.resolve("structure-events.jsonl"),
              json.writeValueAsString(Map.of(
                  "eventType", "bookcast.finalization.failed",
                  "attemptErrorCode", outcome.errorCode(),
                  "finalizationErrors", List.copyOf(finalizationErrors))) + "\n",
              java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
          // Disk failure may prevent all diagnostics; never replace the primary failure.
        }
      }
      phase = Phase.CLOSED;
    }

    private void finalizationFailure(String code) {
      finalizationErrors.add(code);
      if (outcome.errorCode() == null) outcome = Metadata.error(code, false);
    }

    /** Takes ownership on connection completion as well as onOpen, covering either callback order. */
    synchronized void connected(WebSocket ws) {
      if (phase == Phase.CLOSED) {
        if (ws != socket) ws.abort();
      } else socket = ws;
    }

    synchronized AudioResult result() {
      if (phase != Phase.CLOSED) throw new IllegalStateException("RAW_ATTEMPT_NOT_FINALIZED");
      return new AudioResult(
          audio.toByteArray(),
          transcript.toString(),
          new Metadata(
              usage,
              model.equals("gpt-realtime-2.1-mini") ? cost : null,
              outcome.errorCode(), outcome.retryable(), outcome.providerStatus(),
              outcome.providerCode(), outcome.retryAfterMs()));
    }

    private String instructions() {
      return INSTRUCTIONS
          + (group.speaker() == Speaker.HOST
              ? " HOST: curious, intelligent, warm and responsive."
              : " EXPERT: calm, knowledgeable, engaged, explanatory without lecturing.");
    }

    /**
     * Serializes a command and waits up to ten seconds for its WebSocket send to complete.
     * Returns false on terminal state or send/wait failure so callers stop immediately.
     * Serialization exceptions propagate to the calling callback's handler.
     */
    private boolean send(WebSocket ws, Object payload) throws Exception {
      if (done.isDone()) return false;
      String encoded = json.writeValueAsString(payload);
      try {
        ws.sendText(encoded, true).get(10, TimeUnit.SECONDS);
        return !done.isDone();
      } catch (Exception e) {
        failure(OpenAiAudioFailures.exception(e));
        return false;
      }
    }

    /**
     * Sends the requested audio-session configuration, then requests the first inbound callback.
     * Script submission is deferred until a matching {@code session.updated} acknowledgement.
     */
    public synchronized void onOpen(WebSocket ws) {
      connected(ws);
      if (done.isDone()) return;
      phase = Phase.CONFIGURING;
      try {
        if (!send(
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
                            settings.voice(group.speaker()),
                            "speed",
                            settings.speed())))))) return;
      } catch (Exception e) {
        failure(OpenAiAudioFailures.exception(e));
        return;
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
     * <p>On parsed {@code response.done}, valid usage is recorded before structural budgets or
     * status checks. A non-completed
     * response uses its structured provider error if present, otherwise non-retryable
     * {@code INCOMPLETE_AUDIO}. A completed response requires verified configuration, successful
     * response submission and structural validation. Only
     * {@code UNEXPECTED_AUDIO_STRUCTURE} after configuration acknowledgement is marked retryable;
     * unsupported schemas stop without a retry hint. Event decoding and artifact-writing exceptions
     * share the non-retryable {@code PROVIDER_EVENT_FORMAT} classification.
     * Local size/complexity limits are checked before appending or decoding and instead produce
     * non-retryable {@code PROVIDER_RESOURCE_LIMIT}, retaining earlier partial data and accounting.
     *
     * @param ws current provider socket, used to send the script request and request further input
     * @param data next fragment of the current JSON event
     * @param last whether this fragment completes the event message
     * @return an already-completed callback stage; attempt completion is signaled separately through
     *     {@code done}, and callbacks arriving after that completion are ignored
     */
    public synchronized CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      if (done.isDone()) return CompletableFuture.completedFuture(null);
      if (data.length() > limits.messageChars() - message.length()) {
        resourceFailure("json_message_chars");
        return CompletableFuture.completedFuture(null);
      }
      message.append(data);
      if (last) {
        try (var parser = eventJson.createParser(message.toString())) {
          JsonNode e = json.readTree(parser);
          message.setLength(0);
          if (e.path("type").asText().equals("response.done"))
            usage(e.path("response").path("usage"));
          writeString(
              folder.resolve("structure-events.jsonl"),
              json.writeValueAsString(structure.observe(e)) + "\n",
              java.nio.file.StandardOpenOption.APPEND);
          switch (e.path("type").asText()) {
            case "session.updated" -> {
              if (phase == Phase.REQUESTED) break;
              if (phase != Phase.CONFIGURING) {
                fail("UNEXPECTED_AUDIO_LIFECYCLE", false);
                break;
              }
              var s = e.path("session");
              if (!s.path("model").asText().equals(profile().model())
                  || !s.path("audio")
                      .path("output")
                      .path("voice")
                      .asText()
                      .equals(settings.voice(group.speaker()))
                  || s.path("audio").path("output").path("speed").asDouble() != settings.speed()) {
                fail("SESSION_CONFIGURATION_MISMATCH", false);
                break;
              }
              phase = Phase.CONFIGURED;
              if (!send(
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
                                  List.of(Map.of("type", "input_text", "text", group.text()))))))))
                return CompletableFuture.completedFuture(null);
              if (!done.isDone()) phase = Phase.REQUESTED;
            }
            case "response.output_audio.delta" -> {
              String encoded = e.path("delta").asText();
              // Upper bound for the basic decoder's output allocation, including malformed padding.
              int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1
                  : (4 - encoded.length() % 4) % 4;
              long decodedBytes = ((encoded.length() + 3L) / 4) * 3 - padding;
              if (decodedBytes > limits.audioDeltaBytes())
                throw new RealtimeAudioStructure.ResourceLimit("audio_delta_bytes");
              if (decodedBytes > limits.pcmBytes() - audio.size())
                throw new RealtimeAudioStructure.ResourceLimit("pcm_bytes");
              byte[] pcm = Base64.getDecoder().decode(encoded);
              audio.write(pcm);
              spool.write(pcm);
              spool.flush();
            }
            case "response.output_audio_transcript.delta" -> {
              String delta = e.path("delta").asText();
              if (delta.length() > limits.transcriptChars() - transcript.length())
                throw new RealtimeAudioStructure.ResourceLimit("transcript_chars");
              transcript.append(delta);
              writeString(
                  folder.resolve("transcript-deltas.jsonl"),
                  json.writeValueAsString(Map.of("delta", delta)) + "\n",
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.APPEND);
            }
            case "error" -> failure(OpenAiAudioFailures.event(e.path("error")));
            case "response.done" -> {
              var r = e.path("response");
              if (!r.path("status").asText().equals("completed")) {
                if (r.path("status_details").path("error").isObject())
                  failure(OpenAiAudioFailures.event(r.path("status_details").path("error")));
                else fail("INCOMPLETE_AUDIO", false);
              } else if (phase != Phase.REQUESTED) {
                fail("UNEXPECTED_AUDIO_LIFECYCLE", false);
              } else {
                String structuralError = structure.validate();
                if (structuralError != null)
                  fail(
                      structuralError,
                      structuralError.equals("UNEXPECTED_AUDIO_STRUCTURE"));
                else {
                  phase = Phase.COMPLETED;
                  done.complete(null);
                }
              }
            }
          }
        } catch (RealtimeAudioStructure.ResourceLimit e) {
          resourceFailure(e.resource);
        } catch (StreamConstraintsException e) {
          resourceFailure("json_complexity");
        } catch (Exception e) {
          fail("PROVIDER_EVENT_FORMAT", false);
        }
      }
      if (!done.isDone()) ws.request(1);
      return CompletableFuture.completedFuture(null);
    }

    private void resourceFailure(String resource) {
      if (done.isDone()) return;
      resourceLimit = resource;
      fail(RealtimeAudioStructure.ResourceLimit.CODE, false);
    }

    /** Captures known accounting before structural budgets can reject a parsed final response. */
    private void usage(JsonNode u) {
      if (!u.isObject()) return;
      var reported = new HashMap<String, Long>();
      usageToken(reported, "inputTokens", u.path("input_tokens"));
      usageToken(reported, "outputTokens", u.path("output_tokens"));
      usageToken(reported, "inputTextTokens", u.path("input_token_details").path("text_tokens"));
      usageToken(reported, "outputTextTokens", u.path("output_token_details").path("text_tokens"));
      usageToken(reported, "outputAudioTokens", u.path("output_token_details").path("audio_tokens"));
      usage = Map.copyOf(reported);
      if (usage.keySet().containsAll(Set.of("inputTextTokens", "outputTextTokens", "outputAudioTokens")))
        cost = (usage.get("inputTextTokens") * .6 + usage.get("outputTextTokens") * 2.4
            + usage.get("outputAudioTokens") * 20) / 1000000.0;
    }

    /**
     * Records {@code EARLY_SOCKET_CLOSE} only if the attempt has not completed. Codes 1000, 1001,
     * 1006, 1011, 1012 and 1013 are marked retryable; other close codes are not. The provider's
     * human-readable close reason is neither retained nor used for classification.
     */
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
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
     * Records the first terminal failure and its provider metadata atomically. Later callbacks and
     * render-thread failures cannot replace it or discard collected audio, transcript or usage.
     */
    synchronized void failure(Metadata metadata) {
      if (done.isDone()) return;
      outcome = metadata;
      done.complete(null);
    }

    /**
     * Completes a local/protocol failure through the same first-failure latch as provider errors.
     */
    synchronized void fail(String code, boolean retry) {
      failure(Metadata.error(code, retry));
    }
  }
}
