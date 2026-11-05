package com.bookcast.app.generation.adapter.openai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAiSpeechLifecycleTest {
  @TempDir Path data;

  class Fixture {
    final ObjectMapper json;
    final UUID job = UUID.randomUUID(), attempt = UUID.randomUUID();
    final Path folder = data.resolve(job.toString()).resolve(attempt.toString());
    final HttpClient http = mock(HttpClient.class);
    final WebSocket.Builder builder = mock(WebSocket.Builder.class, RETURNS_SELF);
    final WebSocket socket = mock(WebSocket.class);
    final OpenAiSpeech adapter;
    Speaker speaker = Speaker.EXPERT;
    final List<String> events = new ArrayList<>(List.of(
        """
        {"type":"session.updated","session":{"model":"gpt-realtime-2.1-mini",
         "audio":{"output":{"voice":"ash","speed":0.95}}}}
        """,
        """
        {"type":"response.output_audio.delta","response_id":"resp_1","item_id":"msg_1",
         "output_index":0,"content_index":0,"delta":"AQACAA=="}
        """,
        """
        {"type":"response.output_audio_transcript.delta","response_id":"resp_1",
         "item_id":"msg_1","output_index":0,"content_index":0,"delta":"Selitys."}
        """,
        """
        {"type":"response.done","response":{"id":"resp_1","status":"completed",
         "output":[{"id":"msg_1","type":"message","role":"assistant","status":"completed",
                     "content":[{"type":"output_audio"}]}],
         "usage":{"input_tokens":10,"output_tokens":20,"input_token_details":{"text_tokens":10},
                  "output_token_details":{"text_tokens":2,"audio_tokens":18}}}}
        """));
    WebSocket.Listener listener;
    Function<WebSocket.Listener, CompletableFuture<WebSocket>> connect;

    Fixture() {
      this(new ObjectMapper());
    }

    Fixture(ObjectMapper mapper) {
      this(mapper, OpenAiSpeech.Limits.DEFAULT);
    }

    Fixture(OpenAiSpeech.Limits limits) {
      this(new ObjectMapper(), limits);
    }

    Fixture(ObjectMapper mapper, OpenAiSpeech.Limits limits) {
      this(mapper, limits, OpenAiSpeechSettings.DEFAULT, "gpt-realtime-2.1-mini");
    }

    Fixture(ObjectMapper mapper, OpenAiSpeech.Limits limits, OpenAiSpeechSettings settings,
        String model) {
      json = mapper;
      adapter = new OpenAiSpeech(json, "OFFLINE_PRIVATE_KEY", data.toString(),
          model, settings, limits);
      ReflectionTestUtils.setField(adapter, "http", http);
      when(http.newWebSocketBuilder()).thenReturn(builder);
      when(socket.sendText(any(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
      connect = l -> {
        l.onOpen(socket);
        deliver(l);
        return CompletableFuture.completedFuture(socket);
      };
      when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(i -> {
        listener = i.getArgument(1);
        return connect.apply(listener);
      });
    }

    void deliver(WebSocket.Listener l) {
      events.forEach(e -> l.onText(socket, e, true));
    }

    AudioResult render() {
      return adapter.render(
          new Group(0, 0, speaker, "Selitys.", "hash", List.of()), job, attempt);
    }

    JsonNode diagnostics() throws IOException {
      return json.readTree(Files.readString(folder.resolve("response-structure.json")));
    }

    String journal() throws IOException {
      return Files.readString(folder.resolve("structure-events.jsonl"));
    }

    OutputStream failSpoolClose() {
      OutputStream original = (OutputStream) ReflectionTestUtils.getField(listener, "spool");
      OutputStream failing = spy(new FilterOutputStream(original) {
        @Override public void close() throws IOException {
          super.close();
          throw new IOException("private close details");
        }
      });
      ReflectionTestUtils.setField(listener, "spool", failing);
      return failing;
    }
  }

  static void failure(WebSocket.Listener listener, Metadata metadata) {
    ReflectionTestUtils.invokeMethod(listener, "failure", metadata);
  }

  static void assertUsageAndCost(AudioResult result) {
    assertEquals(18L, result.metadata().usage().get("outputAudioTokens"));
    assertEquals(.0003708, result.metadata().cost(), 1e-12);
    assertArrayEquals(new byte[] {1, 0, 2, 0}, result.pcm());
    assertEquals("Selitys.", result.transcript());
  }

  @ParameterizedTest
  @CsvSource({"HOST,marin,ash,0.95,gpt-realtime-2.1-mini", "EXPERT,marin,ash,0.95,gpt-realtime-2.1-mini",
      "HOST,cedar,coral,1.2,custom-model", "EXPERT,cedar,coral,1.2,custom-model"})
  void settingsAgreeAcrossRequestAcknowledgementAndDiagnostics(Speaker speaker, String host,
      String expert, double speed, String model) throws Exception {
    var settings = new OpenAiSpeechSettings(host, expert, speed);
    var f = new Fixture(new ObjectMapper(), OpenAiSpeech.Limits.DEFAULT, settings, model);
    f.speaker = speaker;
    ObjectNode ack = (ObjectNode) f.json.readTree(f.events.getFirst());
    ((ObjectNode) ack.path("session")).put("model", model);
    ((ObjectNode) ack.path("session").path("audio").path("output"))
        .put("voice", settings.voice(speaker)).put("speed", speed);
    f.events.set(0, ack.toString());
    var result = f.render();
    assertNull(result.metadata().errorCode());
    var commands = org.mockito.ArgumentCaptor.forClass(CharSequence.class);
    verify(f.socket, times(2)).sendText(commands.capture(), eq(true));
    JsonNode session = f.json.readTree(commands.getAllValues().getFirst().toString()).path("session");
    assertEquals(model, session.path("model").asText());
    assertEquals(settings.voice(speaker), session.path("audio").path("output").path("voice").asText());
    assertEquals(speed, session.path("audio").path("output").path("speed").asDouble());
    JsonNode config = f.json.readTree(Files.readString(f.folder.resolve("render-config.json")));
    assertEquals(model, config.path("model").asText());
    assertEquals(settings.voice(speaker), config.path("voice").asText());
    assertEquals(speed, config.path("speed").asDouble());
    assertTrue(f.diagnostics().path("configurationValidated").asBoolean());
    assertTrue(f.diagnostics().path("responseSubmitted").asBoolean());
    if (model.equals("custom-model")) assertNull(result.metadata().cost());
    else assertUsageAndCost(result);
  }

  @ParameterizedTest
  @ValueSource(strings = {"model", "voice", "speed", "missingSpeed"})
  void acknowledgementMustMatchConfiguredSettingsBeforeResponseSubmission(String mismatch)
      throws Exception {
    var f = new Fixture(new ObjectMapper(), OpenAiSpeech.Limits.DEFAULT,
        new OpenAiSpeechSettings("cedar", "coral", 1.2), "custom-model");
    ObjectNode ack = (ObjectNode) f.json.readTree(f.events.getFirst());
    ObjectNode session = (ObjectNode) ack.path("session");
    ObjectNode output = (ObjectNode) session.path("audio").path("output");
    session.put("model", mismatch.equals("model") ? "wrong" : "custom-model");
    output.put("voice", mismatch.equals("voice") ? "ash" : "coral")
        .put("speed", mismatch.equals("speed") ? .95 : 1.2);
    if (mismatch.equals("missingSpeed")) output.remove("speed");
    f.events.set(0, ack.toString());
    var result = f.render();
    assertEquals("SESSION_CONFIGURATION_MISMATCH", result.metadata().errorCode());
    assertFalse(result.metadata().retryable());
    verify(f.socket, times(1)).sendText(any(), eq(true));
    assertFalse(f.diagnostics().path("configurationValidated").asBoolean());
    assertFalse(f.diagnostics().path("responseSubmitted").asBoolean());
  }

  @Test
  void firstFailureRetainsItsProviderMetadataThroughCallbacksAndDiagnosticFailure() throws Exception {
    var f = new Fixture();
    var first = new Metadata(Map.of(), null, "PROVIDER_HTTP_429", true, 429, "first_code", 12000L);
    f.connect = l -> {
      l.onOpen(f.socket);
      f.events.subList(0, 3).forEach(e -> l.onText(f.socket, e, true));
      failure(l, first);
      l.onError(f.socket, new IOException("private error"));
      l.onClose(f.socket, 1008, "private reason");
      failure(l, Metadata.error("PROVIDER_PROTOCOL_FAILURE", false));
      f.deliver(l);
      try {
        Files.writeString(f.folder.resolve("response-structure.json"), "existing sidecar",
            StandardOpenOption.CREATE_NEW);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return CompletableFuture.completedFuture(f.socket);
    };
    var result = f.render();
    assertEquals(first, result.metadata());
    assertArrayEquals(new byte[] {1, 0, 2, 0}, result.pcm());
    assertEquals("Selitys.", result.transcript());
    assertEquals("existing sidecar", Files.readString(f.folder.resolve("response-structure.json")));
    assertTrue(f.journal().contains("STRUCTURE_DIAGNOSTIC_WRITE_FAILURE"));
    assertTrue(f.journal().contains("PROVIDER_HTTP_429"));
    verify(f.socket, times(2)).sendText(any(), eq(true));
    String journal = f.journal();
    ReflectionTestUtils.invokeMethod(f.listener, "closeRaw");
    assertEquals(journal, f.journal());
    AudioResult again = ReflectionTestUtils.invokeMethod(f.listener, "result");
    assertEquals(first, again.metadata());
  }

  @Test
  void onCloseCannotOverwriteRenderThreadFailureWhileWaitingForListenerMonitor() throws Exception {
    var f = new Fixture();
    var first = new Metadata(Map.of(), null, "PROVIDER_HTTP_429", true, 429, "first_code", 12000L);
    f.connect = l -> {
      l.onOpen(f.socket);
      Thread closing = new Thread(() -> l.onClose(f.socket, 1008, "ignored"));
      synchronized (l) {
        closing.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (closing.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
          Thread.onSpinWait();
        assertEquals(Thread.State.BLOCKED, closing.getState());
        failure(l, first);
      }
      try {
        closing.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
      assertFalse(closing.isAlive());
      return CompletableFuture.completedFuture(f.socket);
    };
    assertEquals(first, f.render().metadata());
  }

  @Test
  void completedAudioWithoutConfigurationOrResponseSubmissionIsRejected() throws Exception {
    var f = new Fixture();
    f.events.removeFirst();
    var result = f.render();
    assertEquals("UNEXPECTED_AUDIO_LIFECYCLE", result.metadata().errorCode());
    assertFalse(result.metadata().retryable());
    assertUsageAndCost(result);
    assertFalse(f.diagnostics().path("configurationValidated").asBoolean());
    assertFalse(f.diagnostics().path("responseSubmitted").asBoolean());
    verify(f.socket, times(1)).sendText(any(), eq(true));
  }

  @Test
  void failedSessionUpdateStopsBeforeRequestingInputOrSubmittingResponse() throws Exception {
    var f = new Fixture();
    when(f.socket.sendText(any(), eq(true)))
        .thenReturn(CompletableFuture.failedFuture(new IOException("private send failure")));
    var result = f.render();
    assertEquals("PROVIDER_TRANSPORT_FAILURE", result.metadata().errorCode());
    verify(f.socket, times(1)).sendText(any(), eq(true));
    verify(f.socket, never()).request(anyLong());
    assertFalse(f.diagnostics().path("configurationValidated").asBoolean());
    assertFalse(f.diagnostics().path("responseSubmitted").asBoolean());
    assertEquals(0, result.pcm().length);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedOrTimedOutResponseSubmissionCannotBecomeSuccess(boolean timeout) throws Exception {
    var f = new Fixture();
    CompletableFuture<WebSocket> failed = timeout
        ? new CompletableFuture<>() {
          @Override public WebSocket get(long wait, TimeUnit unit) throws TimeoutException {
            throw new TimeoutException();
          }
        }
        : CompletableFuture.failedFuture(new IOException("private send failure"));
    when(f.socket.sendText(any(), eq(true)))
        .thenReturn(CompletableFuture.completedFuture(f.socket), failed);
    f.events.add(1, f.events.getFirst());
    var result = f.render();
    assertEquals(timeout ? "PROVIDER_TIMEOUT" : "PROVIDER_TRANSPORT_FAILURE",
        result.metadata().errorCode());
    verify(f.socket, times(2)).sendText(any(), eq(true));
    verify(f.socket, times(1)).request(1);
    assertTrue(f.diagnostics().path("configurationValidated").asBoolean());
    assertFalse(f.diagnostics().path("responseSubmitted").asBoolean());
    assertNull(result.metadata().cost());
  }

  @Test
  void successfulSubmissionAndCompletionIgnoreLateTransportFailures() throws Exception {
    var f = new Fixture();
    f.events.add(1, f.events.getFirst());
    f.connect = l -> {
      l.onOpen(f.socket);
      f.deliver(l);
      l.onClose(f.socket, 1011, "ignored");
      l.onError(f.socket, new IOException("ignored"));
      failure(l, Metadata.error("PROVIDER_TIMEOUT", true));
      return CompletableFuture.completedFuture(f.socket);
    };
    var result = f.render();
    assertNull(result.metadata().errorCode());
    assertUsageAndCost(result);
    assertTrue(f.diagnostics().path("configurationValidated").asBoolean());
    assertTrue(f.diagnostics().path("responseSubmitted").asBoolean());
    verify(f.socket, times(2)).sendText(any(), eq(true));
    verify(f.socket).abort();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abandonedConnectionIsCancelledAndLateOpenCannotIssueCommands(boolean interrupt) {
    var f = new Fixture();
    CompletableFuture<WebSocket> pending = new CompletableFuture<>() {
      @Override public WebSocket get(long wait, TimeUnit unit)
          throws TimeoutException, InterruptedException {
        if (interrupt) throw new InterruptedException();
        throw new TimeoutException();
      }
    };
    f.connect = l -> pending;
    AudioResult result;
    try {
      result = f.render();
      assertEquals(interrupt, Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(interrupt ? "PROCESS_INTERRUPTED" : "PROVIDER_TIMEOUT",
        result.metadata().errorCode());
    assertTrue(pending.isCancelled());
    f.listener.onOpen(f.socket);
    verify(f.socket).abort();
    verify(f.socket, never()).sendText(any(), anyBoolean());
    verify(f.socket, never()).request(anyLong());
  }

  @Test
  void uncancellableConnectionDeliveredAfterTimeoutIsAbortedWithoutWaitingForOnOpen() {
    var f = new Fixture();
    CompletableFuture<WebSocket> pending = new CompletableFuture<>() {
      @Override public WebSocket get(long wait, TimeUnit unit) throws TimeoutException {
        throw new TimeoutException();
      }
      @Override public boolean cancel(boolean interrupt) { return false; }
    };
    f.connect = l -> pending;
    var result = f.render();
    assertEquals("PROVIDER_TIMEOUT", result.metadata().errorCode());
    assertTrue(pending.complete(f.socket));
    verify(f.socket).abort();
    verify(f.socket, never()).sendText(any(), anyBoolean());
  }

  @Test
  void connectionCompletedBeforeOnOpenIsOwnedAndClosedOnFailure() {
    var f = new Fixture();
    CompletableFuture<WebSocket> connected = new CompletableFuture<>() {
      @Override public WebSocket get(long wait, TimeUnit unit)
          throws InterruptedException, ExecutionException, TimeoutException {
        failure(f.listener, Metadata.error("PROVIDER_TIMEOUT", true));
        return super.get(wait, unit);
      }
    };
    connected.complete(f.socket);
    f.connect = l -> connected;
    assertEquals("PROVIDER_TIMEOUT", f.render().metadata().errorCode());
    verify(f.socket).abort();
    f.listener.onOpen(f.socket);
    verify(f.socket, never()).sendText(any(), anyBoolean());
  }

  @Test
  void interruptionClosesSocketOpenedBeforeConnectionFutureCompletes() {
    var f = new Fixture();
    CompletableFuture<WebSocket> pending = new CompletableFuture<>() {
      @Override public WebSocket get(long wait, TimeUnit unit) throws InterruptedException {
        throw new InterruptedException();
      }
    };
    f.connect = l -> {
      l.onOpen(f.socket);
      return pending;
    };
    AudioResult result;
    try {
      result = f.render();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals("PROCESS_INTERRUPTED", result.metadata().errorCode());
    assertFalse(result.metadata().retryable());
    assertTrue(pending.isCancelled());
    verify(f.socket).abort();
    verify(f.socket, times(1)).sendText(any(), eq(true));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void spoolCloseFailureIsObservableWithoutReplacingProviderFailure(boolean providerFailed)
      throws Exception {
    var f = new Fixture();
    if (providerFailed) {
      ObjectNode done = (ObjectNode) f.json.readTree(f.events.getLast());
      ObjectNode response = (ObjectNode) done.path("response");
      response.put("status", "failed");
      response.putObject("status_details").putObject("error")
          .put("type", "rate_limit_error").put("code", "insufficient_quota");
      f.events.set(f.events.size() - 1, done.toString());
    }
    f.connect = l -> {
      l.onOpen(f.socket);
      f.deliver(l);
      f.failSpoolClose();
      return CompletableFuture.completedFuture(f.socket);
    };
    var result = f.render();
    assertEquals(providerFailed ? "PROVIDER_QUOTA" : "RAW_ATTEMPT_CLOSE_FAILURE",
        result.metadata().errorCode());
    assertFalse(result.metadata().retryable());
    assertEquals(providerFailed ? "insufficient_quota" : null, result.metadata().providerCode());
    assertUsageAndCost(result);
    assertEquals("RAW_ATTEMPT_CLOSE_FAILURE",
        f.diagnostics().path("finalizationErrors").get(0).asText());
    OutputStream spool = (OutputStream) ReflectionTestUtils.getField(f.listener, "spool");
    ReflectionTestUtils.invokeMethod(f.listener, "closeRaw");
    verify(spool, times(1)).close();
    assertArrayEquals(result.pcm(), Files.readAllBytes(f.folder.resolve("stream.pcm")));
  }

  @Test
  void diagnosticFailureRejectsSuccessAndPreservesExistingSidecarAndCost() throws Exception {
    var f = new Fixture();
    f.connect = l -> {
      l.onOpen(f.socket);
      f.deliver(l);
      try {
        Files.writeString(f.folder.resolve("response-structure.json"), "existing sidecar",
            StandardOpenOption.CREATE_NEW);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return CompletableFuture.completedFuture(f.socket);
    };
    var result = f.render();
    assertEquals("STRUCTURE_DIAGNOSTIC_WRITE_FAILURE", result.metadata().errorCode());
    assertFalse(result.metadata().retryable());
    assertUsageAndCost(result);
    assertEquals("existing sidecar", Files.readString(f.folder.resolve("response-structure.json")));
    assertTrue(f.journal().contains("bookcast.finalization.failed"));
  }

  @Test
  void bothDiagnosticWritesFailWithoutReplacingPrimaryFailure() {
    var f = new Fixture();
    var first = new Metadata(Map.of(), null, "PROVIDER_HTTP_429", true, 429, "first_code", 12000L);
    f.connect = l -> {
      l.onOpen(f.socket);
      failure(l, first);
      return CompletableFuture.completedFuture(f.socket);
    };
    try (var files = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("writeString")
          && (invocation.getArgument(0).equals(f.folder.resolve("response-structure.json"))
              || invocation.getArgument(1).toString().contains("bookcast.finalization.failed")))
        throw new IOException("synthetic disk failure");
      return invocation.callRealMethod();
    })) {
      assertEquals(first, f.render().metadata());
    }
  }

  @Test
  void socketAbortFailureStillFinalizesSpoolAndRetainsReportedCost() throws Exception {
    var f = new Fixture();
    doThrow(new IllegalStateException("private abort failure")).when(f.socket).abort();
    var result = f.render();
    assertEquals("SOCKET_ABORT_FAILURE", result.metadata().errorCode());
    assertUsageAndCost(result);
    assertEquals("SOCKET_ABORT_FAILURE", f.diagnostics().path("finalizationErrors").get(0).asText());
    assertArrayEquals(result.pcm(), Files.readAllBytes(f.folder.resolve("stream.pcm")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void initializationFailureClosesSpoolAndReportsSafeCloseFailure(boolean closeFails)
      throws Exception {
    ObjectMapper mapper = spy(new ObjectMapper());
    var f = new Fixture(mapper);
    f.adapter.prepareGeneration(f.job);
    doThrow(new JsonProcessingException("private configuration failure") {})
        .when(mapper).writeValueAsString(any());
    OutputStream stream = mock(OutputStream.class);
    if (closeFails) doThrow(new IOException("private close failure")).when(stream).close();
    try (var files = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("newOutputStream")
          && invocation.getArgument(0).equals(f.folder.resolve("stream.pcm"))) return stream;
      return invocation.callRealMethod();
    })) {
      var error = assertThrows(IllegalStateException.class, f::render);
      assertEquals("RAW_ATTEMPT_WRITE_FAILURE", error.getMessage());
      assertNull(error.getCause());
      assertEquals(closeFails ? 1 : 0, error.getSuppressed().length);
      if (closeFails)
        assertEquals("RAW_ATTEMPT_CLOSE_FAILURE", error.getSuppressed()[0].getMessage());
      verify(stream).close();
      verifyNoInteractions(f.http);
    }
  }

  void assertResourceFailure(Fixture f, AudioResult result, String resource) throws Exception {
    assertEquals("PROVIDER_RESOURCE_LIMIT", result.metadata().errorCode());
    assertFalse(result.metadata().retryable());
    assertEquals(resource, f.diagnostics().path("resourceLimit").asText());
    verify(f.socket).abort();
    assertArrayEquals(result.pcm(), Files.readAllBytes(f.folder.resolve("stream.pcm")));
  }

  @Test
  void unfinishedJsonFragmentsAreCheckedBeforeAppendWithoutIntegerOverflow() throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(8, 4, 8, 8, 10, 128));
    CharSequence huge = new CharSequence() {
      public int length() { return Integer.MAX_VALUE; }
      public char charAt(int index) { throw new AssertionError("must not read oversized input"); }
      public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
    };
    f.connect = l -> {
      l.onOpen(f.socket);
      l.onText(f.socket, "12345678", false);
      CompletableFuture<?> done = (CompletableFuture<?>) ReflectionTestUtils.getField(l, "done");
      assertFalse(done.isDone(), "an exact-boundary fragment is accepted");
      l.onText(f.socket, huge, false);
      assertTrue(done.isDone());
      f.deliver(l);
      return CompletableFuture.completedFuture(f.socket);
    };
    var result = f.render();
    assertResourceFailure(f, result, "json_message_chars");
    verify(f.socket, times(1)).sendText(any(), eq(true));
    assertNull(result.metadata().cost());
  }

  @Test
  void exactMessageAudioTranscriptAndEventBoundariesAreAccepted() throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(1024, 4, 4, 8, 4, 128));
    String last = f.events.getLast();
    f.events.set(3, last + " ".repeat(1024 - last.length()));
    var result = f.render();
    assertNull(result.metadata().errorCode());
    assertUsageAndCost(result);
    assertEquals(4, f.diagnostics().path("observedEventCount").asInt());
  }

  @ParameterizedTest
  @CsvSource({"AQ==,1", "AQ,1", "AQI=,2", "AQI,2", "AQID,3", "AQIDBA==,4"})
  void paddedAndUnpaddedBase64AtExactDecodedBoundaryIsAccepted(String encoded, int bytes)
      throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(1024, bytes, bytes, 8, 4, 128));
    ObjectNode delta = (ObjectNode) f.json.readTree(f.events.get(1));
    delta.put("delta", encoded);
    f.events.set(1, delta.toString());
    var result = f.render();
    assertNull(result.metadata().errorCode());
    assertArrayEquals(Base64.getDecoder().decode(encoded), result.pcm());
    assertEquals(bytes, Files.size(f.folder.resolve("stream.pcm")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"AQACAA==", "!!!!AA==", "AA==A=="})
  void oversizedAudioIsRejectedBeforeDecodeIncludingMalformedPadding(String encoded)
      throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(1024, 3, 8, 8, 10, 128));
    ObjectNode delta = (ObjectNode) f.json.readTree(f.events.get(1));
    delta.put("delta", encoded);
    f.events.set(1, delta.toString());
    var result = f.render();
    assertResourceFailure(f, result, "audio_delta_bytes");
    assertEquals(0, result.pcm().length);
    verify(f.socket, times(2)).sendText(any(), eq(true));
  }

  @Test
  void cumulativePcmLimitPreservesEarlierAudioAndTranscriptWithoutTruncatingNewDelta()
      throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(1024, 4, 7, 8, 10, 128));
    f.events.add(3, f.events.get(1));
    f.events.add(f.events.getFirst());
    var result = f.render();
    assertResourceFailure(f, result, "pcm_bytes");
    assertArrayEquals(new byte[] {1, 0, 2, 0}, result.pcm());
    assertEquals("Selitys.", result.transcript());
    assertNull(result.metadata().cost());
    assertTrue(result.metadata().usage().isEmpty());
    assertTrue(Files.readString(f.folder.resolve("transcript-deltas.jsonl")).contains("Selitys."));
    verify(f.socket, times(2)).sendText(any(), eq(true));
  }

  @Test
  void cumulativeTranscriptLimitRejectsWholeDeltaAndKeepsEarlierArtifacts() throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(1024, 4, 4, 10, 10, 128));
    ObjectNode delta = (ObjectNode) f.json.readTree(f.events.get(2));
    delta.put("delta", "abc");
    f.events.add(3, delta.toString());
    var result = f.render();
    assertResourceFailure(f, result, "transcript_chars");
    assertEquals("Selitys.", result.transcript());
    assertFalse(Files.readString(f.folder.resolve("transcript-deltas.jsonl")).contains("abc"));
    verify(f.socket, times(2)).sendText(any(), eq(true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"structural_events", "structural_entries"})
  void structuralLimitsKeepUsageFromRejectedFinalResponse(String resource) throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(1024, 4, 4, 8,
        resource.equals("structural_events") ? 3 : 4,
        resource.equals("structural_entries") ? 10 : 128));
    var result = f.render();
    assertResourceFailure(f, result, resource);
    assertUsageAndCost(result);
    verify(f.socket, times(2)).sendText(any(), eq(true));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void jsonDepthAndTokenBudgetsStopMalformedTrees(boolean deep) throws Exception {
    var f = new Fixture();
    String tree = deep ? "[".repeat(65) + "0" + "]".repeat(65)
        : "[" + "0,".repeat(16_384) + "0]";
    f.events.addFirst("{\"type\":\"session.created\",\"unused\":" + tree + "}");
    var result = f.render();
    assertResourceFailure(f, result, "json_complexity");
    verify(f.socket, times(1)).sendText(any(), eq(true));
  }

  @Test
  void oversizedJsonFieldNamesCannotGrowTheParserNameCache() throws Exception {
    var f = new Fixture();
    f.events.addFirst("{\"" + "x".repeat(257) + "\":0}");
    assertResourceFailure(f, f.render(), "json_complexity");
  }

  @Test
  void existingPrimaryFailureStopsEvenInspectingFurtherOversizedInput() throws Exception {
    var f = new Fixture(new OpenAiSpeech.Limits(1024, 4, 4, 8, 4, 128));
    var first = new Metadata(Map.of(), null, "PROVIDER_HTTP_429", true, 429, "first_code", 12000L);
    CharSequence late = new CharSequence() {
      public int length() { throw new AssertionError("terminal input must be ignored"); }
      public char charAt(int index) { throw new AssertionError(); }
      public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
    };
    f.connect = l -> {
      l.onOpen(f.socket);
      f.events.subList(0, 3).forEach(e -> l.onText(f.socket, e, true));
      failure(l, first);
      l.onText(f.socket, late, false);
      return CompletableFuture.completedFuture(f.socket);
    };
    assertEquals(first, f.render().metadata());
    verify(f.socket, times(2)).sendText(any(), eq(true));
  }

  @Test
  void representative614EventAudioStreamRemainsAccepted() throws Exception {
    var f = new Fixture();
    String acknowledgement = f.events.getFirst(), completed = f.events.getLast();
    ObjectNode audio = (ObjectNode) f.json.readTree(f.events.get(1));
    audio.put("delta", Base64.getEncoder().encodeToString(new byte[320]));
    ObjectNode transcript = (ObjectNode) f.json.readTree(f.events.get(2));
    transcript.put("delta", "ää");
    f.events.clear();
    f.events.add(acknowledgement);
    for (int i = 0; i < 306; i++) {
      f.events.add(audio.toString());
      f.events.add(transcript.toString());
    }
    f.events.add(completed);
    var result = f.render();
    assertNull(result.metadata().errorCode());
    assertEquals(306 * 320, result.pcm().length);
    assertEquals(612, result.transcript().length());
    assertEquals(614, f.diagnostics().path("observedEventCount").asInt());
    assertTrue(f.diagnostics().path("structuralEntries").asInt() < 1024);
  }
}
