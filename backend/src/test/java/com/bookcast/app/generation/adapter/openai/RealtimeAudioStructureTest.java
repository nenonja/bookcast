package com.bookcast.app.generation.adapter.openai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.application.Capabilities.AudioResult;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class RealtimeAudioStructureTest {
  @TempDir Path data;
  final ObjectMapper json = new ObjectMapper();

  record Render(AudioResult result, JsonNode diagnostics, String journal) {}

  ObjectNode item(String id, String type, String role, List<String> parts) {
    var item =
        json.createObjectNode()
            .put("id", id)
            .put("type", type)
            .put("role", role)
            .put("status", "completed");
    var content = item.putArray("content");
    for (String part : parts)
      content.addObject().put("type", part).put("transcript", "RAW_PRIVATE_TRANSCRIPT");
    item.put("unsafe_extra_field", "PROMPT_PRIVATE");
    return item;
  }

  ObjectNode audio() {
    return item("msg_1", "message", "assistant", List.of("output_audio"));
  }

  ObjectNode event(String type) {
    return json.createObjectNode().put("type", type);
  }

  ObjectNode coordinate(String type, int output) {
    return event(type)
        .put("response_id", "resp_1")
        .put("item_id", "msg_1")
        .put("output_index", output)
        .put("content_index", 0);
  }

  Render render(List<ObjectNode> output, String status, Consumer<List<ObjectNode>> mutate)
      throws Exception {
    var adapter = new OpenAiSpeech(json, "SECRET_PRIVATE", data.toString());
    HttpClient http = mock(HttpClient.class);
    ReflectionTestUtils.setField(adapter, "http", http);
    var builder = mock(WebSocket.Builder.class, RETURNS_SELF);
    var socket = mock(WebSocket.class);
    when(http.newWebSocketBuilder()).thenReturn(builder);
    when(socket.sendText(any(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
    int index = 0;
    for (int i = 0; i < output.size(); i++)
      if (output.get(i).path("id").asText().equals("msg_1")) index = i;
    var events = new ArrayList<ObjectNode>();
    var updated = event("session.updated");
    updated.set(
        "session",
        json.readTree(
            "{\"model\":\"gpt-realtime-2.1-mini\",\"audio\":{\"output\":{\"voice\":\"ash\",\"speed\":0.95}}}"));
    events.add(updated);
    var created = event("response.created");
    created.set(
        "response", json.createObjectNode().put("id", "resp_1").put("status", "in_progress"));
    events.add(created);
    var added =
        event("response.output_item.added").put("response_id", "resp_1").put("output_index", index);
    added.set(
        "item", item("msg_1", "message", "assistant", List.of()).put("status", "in_progress"));
    events.add(added);
    var part = coordinate("response.content_part.added", index);
    part.set("part", json.createObjectNode().put("type", "output_audio"));
    events.add(part);
    events.add(coordinate("response.output_audio.delta", index).put("delta", "AQACAA=="));
    events.add(
        coordinate("response.output_audio_transcript.delta", index)
            .put("delta", "SPOKEN_PRIVATE."));
    events.add(coordinate("response.output_audio.done", index));
    events.add(
        coordinate("response.output_audio_transcript.done", index)
            .put("transcript", "SPOKEN_PRIVATE."));
    var itemDone =
        event("response.output_item.done").put("response_id", "resp_1").put("output_index", index);
    itemDone.set("item", audio());
    events.add(itemDone);
    var done = event("response.done");
    var response =
        json.createObjectNode()
            .put("id", "resp_1")
            .put("status", status)
            .put("unsafe_extra", "RAW_PRIVATE_BODY");
    response.set("output", json.valueToTree(output));
    response.set(
        "usage",
        json.readTree(
            "{\"input_tokens\":10,\"output_tokens\":20,\"input_token_details\":{\"text_tokens\":10},\"output_token_details\":{\"text_tokens\":2,\"audio_tokens\":18}}"));
    done.set("response", response);
    events.add(done);
    mutate.accept(events);
    when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
        .thenAnswer(
            i -> {
              WebSocket.Listener listener = i.getArgument(1);
              listener.onOpen(socket);
              for (var e : events) listener.onText(socket, json.writeValueAsString(e), true);
              return CompletableFuture.completedFuture(socket);
            });
    UUID job = UUID.randomUUID(), attempt = UUID.randomUUID();
    var result =
        adapter.render(
            new Group(0, 0, Speaker.EXPERT, "SOURCE_PRIVATE", "hash", List.of()), job, attempt);
    var folder = data.resolve(job.toString()).resolve(attempt.toString());
    verify(builder, times(1)).buildAsync(any(), any());
    verify(socket).abort();
    assertArrayEquals(result.pcm(), Files.readAllBytes(folder.resolve("stream.pcm")));
    return new Render(
        result,
        json.readTree(Files.readString(folder.resolve("response-structure.json"))),
        Files.readString(folder.resolve("structure-events.jsonl")));
  }

  Render render(List<ObjectNode> output) throws Exception {
    return render(output, "completed", e -> {});
  }

  void anomaly(Render r) {
    assertEquals("UNEXPECTED_AUDIO_STRUCTURE", r.result().metadata().errorCode());
    assertTrue(r.result().metadata().retryable());
  }

  @Test
  void completedAssistantAudioIsAcceptedAndDiagnosticsAreSafe() throws Exception {
    var r = render(List.of(audio()));
    assertNull(r.result().metadata().errorCode());
    var d = r.diagnostics();
    assertEquals("resp_1", d.path("responseId").asText());
    assertEquals("completed", d.path("finalResponseStatus").asText());
    assertEquals(1, d.path("outputItemCount").asInt());
    assertEquals("message", d.path("outputItems").get(0).path("type").asText());
    assertEquals("assistant", d.path("outputItems").get(0).path("role").asText());
    assertEquals("msg_1", d.path("audioBearingItemId").asText());
    assertEquals("msg_1", d.path("audioDeltaItemIds").get(0).asText());
    assertEquals("msg_1", d.path("transcriptDeltaItemIds").get(0).asText());
    assertEquals(1, d.path("providerEventCounts").path("response.output_audio.delta").asInt());
    for (String secret :
        List.of(
            "SOURCE_PRIVATE",
            "PROMPT_PRIVATE",
            "SECRET_PRIVATE",
            "RAW_PRIVATE",
            "SPOKEN_PRIVATE",
            "AQACAA==")) {
      assertFalse(d.toString().contains(secret));
      assertFalse(r.journal().contains(secret));
    }
    assertTrue(d.path("configurationValidated").asBoolean());
  }

  @Test
  void emptyCompletedAssistantEnvelopeIsExplicitlyHarmlessEvenBeforeAudio() throws Exception {
    var r = render(List.of(item("empty_1", "message", "assistant", List.of()), audio()));
    assertNull(r.result().metadata().errorCode());
    assertEquals(2, r.diagnostics().path("outputItemCount").asInt());
    assertEquals(1, r.diagnostics().path("audioCoordinates").get(0).path("outputIndex").asInt());
  }

  @Test
  void twoAssistantAudioMessagesAreRejected() throws Exception {
    anomaly(
        render(List.of(audio(), item("msg_2", "message", "assistant", List.of("output_audio")))));
  }

  @Test
  void actionableOutputsAlongsideAudioAreRejected() throws Exception {
    for (String type :
        List.of(
            "function_call", "tool_call", "mcp_call", "mcp_list_tools", "mcp_approval_request")) {
      var r = render(List.of(audio(), item("call_1", type, "assistant", List.of())));
      anomaly(r);
      assertEquals(type, r.diagnostics().path("unexpectedItemTypes").get(0).asText());
    }
  }

  @Test
  void multipleAudioDeltaItemIdsAreRejected() throws Exception {
    var r =
        render(
            List.of(audio()),
            "completed",
            e ->
                e.add(
                    5,
                    coordinate("response.output_audio.delta", 0)
                        .put("item_id", "msg_2")
                        .put("delta", "AQACAA==")));
    anomaly(r);
    assertEquals(2, r.diagnostics().path("audioDeltaItemIds").size());
  }

  @Test
  void transcriptItemMismatchIsRejected() throws Exception {
    anomaly(
        render(
            List.of(audio()),
            "completed",
            events ->
                events.stream()
                    .filter(
                        e ->
                            e.path("type")
                                .asText()
                                .equals("response.output_audio_transcript.delta"))
                    .forEach(e -> e.put("item_id", "other_1"))));
  }

  @Test
  void responseIndexAndContentCorrelationAreRequired() throws Exception {
    for (String field : List.of("response_id", "output_index", "content_index")) {
      anomaly(
          render(
              List.of(audio()),
              "completed",
              events ->
                  events.stream()
                      .filter(e -> e.path("type").asText().equals("response.output_audio.delta"))
                      .forEach(
                          e -> {
                            if (field.equals("response_id")) e.put(field, "other_response");
                            else e.put(field, 99);
                          })));
    }
  }

  @Test
  void missingDeltaIdentifiersFailSafely() throws Exception {
    anomaly(
        render(
            List.of(audio()),
            "completed",
            events ->
                events.stream()
                    .filter(e -> e.path("type").asText().equals("response.output_audio.delta"))
                    .forEach(e -> e.remove("item_id"))));
  }

  @Test
  void incompleteAndFailedResponsesNeverPass() throws Exception {
    for (String status : List.of("incomplete", "failed")) {
      var r = render(List.of(audio()), status, e -> {});
      assertEquals("INCOMPLETE_AUDIO", r.result().metadata().errorCode());
      assertEquals(status, r.diagnostics().path("finalResponseStatus").asText());
    }
  }

  @Test
  void textOnlyExtrasAreNotHarmless() throws Exception {
    anomaly(
        render(List.of(audio(), item("text_1", "message", "assistant", List.of("output_text")))));
  }

  @Test
  void unknownItemOrContentSchemaStopsWithoutRetry() throws Exception {
    for (var output :
        List.of(
            List.of(audio(), item("unknown_1", "future_schema", "assistant", List.of())),
            List.of(item("msg_1", "message", "assistant", List.of("future_audio_type"))))) {
      var r = render(output);
      assertEquals("PROVIDER_SCHEMA_UNSUPPORTED", r.result().metadata().errorCode());
      assertFalse(r.result().metadata().retryable());
    }
  }

  @Test
  void configurationFailureNeverBecomesRetryableStructureFailure() throws Exception {
    var r =
        render(
            List.of(audio()),
            "completed",
            events ->
                ((ObjectNode) events.getFirst().path("session").path("audio").path("output"))
                    .put("voice", "unsupported"));
    assertEquals("SESSION_CONFIGURATION_MISMATCH", r.result().metadata().errorCode());
    assertFalse(r.result().metadata().retryable());
    assertFalse(r.diagnostics().path("configurationValidated").asBoolean());
  }

  @Test
  void assistantRoleCompletedItemAndAudioContentAreRequired() throws Exception {
    for (var invalid :
        List.of(
            audio().put("role", "user"),
            audio().put("status", "incomplete"),
            item("msg_1", "message", "assistant", List.of()))) anomaly(render(List.of(invalid)));
  }

  @Test
  void missingCredentialsStillPreserveEmptySafeDiagnosticsWithoutConnecting() throws Exception {
    var adapter = new OpenAiSpeech(json, "", data.toString());
    HttpClient http = mock(HttpClient.class);
    ReflectionTestUtils.setField(adapter, "http", http);
    UUID job = UUID.randomUUID(), attempt = UUID.randomUUID();
    var result =
        adapter.render(
            new Group(0, 0, Speaker.EXPERT, "SOURCE_PRIVATE", "hash", List.of()), job, attempt);
    assertEquals("API_KEY_MISSING", result.metadata().errorCode());
    verifyNoInteractions(http);
    var diagnostics =
        json.readTree(
            Files.readString(
                data.resolve(job.toString())
                    .resolve(attempt.toString())
                    .resolve("response-structure.json")));
    assertEquals("API_KEY_MISSING", diagnostics.path("attemptErrorCode").asText());
    assertFalse(diagnostics.toString().contains("SOURCE_PRIVATE"));
  }

  @Test
  void streamingItemIdentityCannotChangeBeforeFinalResponse() throws Exception {
    anomaly(
        render(
            List.of(audio()),
            "completed",
            events ->
                events.stream()
                    .filter(e -> e.path("type").asText().equals("response.output_item.added"))
                    .forEach(e -> ((ObjectNode) e.path("item")).put("id", "different_1"))));
  }

  @Test
  void completedItemEventMustAgreeWithFinalItem() throws Exception {
    anomaly(
        render(
            List.of(audio()),
            "completed",
            events ->
                events.stream()
                    .filter(e -> e.path("type").asText().equals("response.output_item.done"))
                    .forEach(e -> ((ObjectNode) e.path("item")).put("status", "incomplete"))));
  }
}
