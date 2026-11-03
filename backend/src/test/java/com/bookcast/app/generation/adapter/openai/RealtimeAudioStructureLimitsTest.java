package com.bookcast.app.generation.adapter.openai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RealtimeAudioStructureLimitsTest {
  final ObjectMapper json = new ObjectMapper();

  ObjectNode event(String type) {
    return json.createObjectNode().put("type", type);
  }

  @Test
  void exactEventLimitIsAcceptedAndFurtherObservationsCannotGrowState() {
    var structure = new RealtimeAudioStructure(3, 10);
    for (int i = 0; i < 3; i++) structure.observe(event("session.created"));
    assertEquals(3, structure.snapshot().get("observedEventCount"));
    var failure = assertThrows(RealtimeAudioStructure.ResourceLimit.class,
        () -> structure.observe(event("session.created")));
    assertEquals("structural_events", failure.resource);
    assertEquals("PROVIDER_RESOURCE_LIMIT", structure.validate());
    var after = structure.snapshot();
    assertThrows(RealtimeAudioStructure.ResourceLimit.class,
        () -> structure.observe(event("another_type")));
    assertEquals(after, structure.snapshot());
    assertEquals(Map.of("session.created", 3L), after.get("providerEventCounts"));
  }

  @Test
  void distinctEventTypeCountersConsumeTheSharedBudgetBeforeInsertion() throws Exception {
    var structure = new RealtimeAudioStructure(10, 3);
    for (int i = 0; i < 3; i++) structure.observe(event("event_" + i));
    assertEquals(3, structure.snapshot().get("structuralEntries"));
    assertThrows(RealtimeAudioStructure.ResourceLimit.class,
        () -> structure.observe(event("event_3")));
    var diagnostics = structure.snapshot();
    assertEquals(3, ((Map<?, ?>) diagnostics.get("providerEventCounts")).size());
    assertEquals(3, diagnostics.get("structuralEntries"));
    assertEquals("structural_entries", diagnostics.get("resourceLimit"));
    assertTrue(json.writeValueAsString(diagnostics).length() < 2000);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "response.output_audio.delta", "response.output_audio_transcript.delta", "response.output_text.delta"
  })
  void repeatedCoordinatesAreDeduplicatedAndNewCoordinatesCannotExceedBudget(String type) {
    int entries = type.equals("response.output_text.delta") ? 3 : 4;
    var structure = new RealtimeAudioStructure(20, entries);
    var delta = event(type).put("response_id", "resp_1").put("item_id", "msg_1")
        .put("output_index", 0).put("content_index", 0);
    for (int i = 0; i < 10; i++) structure.observe(delta);
    assertEquals(entries, structure.snapshot().get("structuralEntries"));
    delta.put("item_id", "other");
    assertThrows(RealtimeAudioStructure.ResourceLimit.class, () -> structure.observe(delta));
    assertEquals("PROVIDER_RESOURCE_LIMIT", structure.validate());
    assertEquals(entries, structure.snapshot().get("structuralEntries"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void outputAndContentArraysAreCheckedBeforeTraversalOrOverflow(boolean contentParts) {
    var structure = new RealtimeAudioStructure(10, Integer.MAX_VALUE);
    JsonNode array = mock(JsonNode.class);
    when(array.isArray()).thenReturn(true);
    when(array.size()).thenReturn(Integer.MAX_VALUE);
    when(array.iterator()).thenThrow(new AssertionError("must reject before traversal"));
    ObjectNode input;
    if (contentParts) {
      input = event("response.output_item.added").put("response_id", "resp_1").put("output_index", 0);
      input.putObject("item").put("id", "msg_1").put("type", "message")
          .put("role", "assistant").put("status", "completed").set("content", array);
    } else {
      input = event("response.done");
      input.putObject("response").put("id", "resp_1").put("status", "completed")
          .set("output", array);
    }
    var error = assertThrows(RealtimeAudioStructure.ResourceLimit.class, () -> structure.observe(input));
    assertEquals("structural_entries", error.resource);
    verify(array, never()).iterator();
    assertTrue((int) structure.snapshot().get("structuralEntries") < 10);
  }

  @Test
  void repeatedItemDefinitionsAlsoConsumeBudgetInsteadOfChurningUnboundedSummaries() {
    var structure = new RealtimeAudioStructure(100, 8);
    var input = event("response.output_item.added").put("response_id", "resp_1")
        .put("output_index", 0);
    input.putObject("item").put("id", "msg_1").put("type", "message")
        .put("role", "assistant").put("status", "completed").putArray("content");
    for (int i = 0; i < 5; i++) structure.observe(input);
    assertEquals(8, structure.snapshot().get("structuralEntries"));
    assertThrows(RealtimeAudioStructure.ResourceLimit.class, () -> structure.observe(input));
    assertEquals("PROVIDER_RESOURCE_LIMIT", structure.validate());
  }
}
