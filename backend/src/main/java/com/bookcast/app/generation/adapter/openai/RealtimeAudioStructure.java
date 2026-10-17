package com.bookcast.app.generation.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Collects structural evidence for one Realtime speech response and checks its event correlation.
 *
 * <p>Each speech-rendering attempt owns a fresh instance. Acceptance requires one completed assistant
 * message containing only {@code output_audio}; additional completed assistant messages must have
 * empty content. Audio and transcript deltas must identify that same message, and all recorded audio
 * and transcript coordinates must match its response ID, output index and content index zero.
 * Observed streaming items must agree with the final response's item identities.
 *
 * <p>Diagnostics retain only selected identifiers, schema tokens, indices, item summaries and event
 * counts. Provider strings are restricted to 1–200 ASCII letters, digits, underscores, dots or
 * hyphens. Event bodies, audio bytes, transcript text and usage are not retained. This is structural
 * validation, not a check of audible completeness or fidelity to the requested script.
 *
 * <p>The speech adapter owns transport, session-configuration checks, payload preservation and the
 * retryability decision. This collector neither writes artifacts nor retries provider calls. Its
 * observation, validation and snapshot operations are synchronized, but there is no reset or terminal
 * state preventing further observations; the caller controls the response lifecycle.
 */
final class RealtimeAudioStructure {
  /**
   * Payload-free summary of an output item in a streaming event or the final response.
   *
   * @param id sanitized provider item ID, or {@code null} if absent or invalid
   * @param outputIndex zero-based output position, or {@code -1} for an invalid streaming index
   * @param type sanitized item type, or {@code null} if absent or invalid
   * @param role sanitized message role, or {@code null} if absent or invalid
   * @param status sanitized item status, or {@code null} if absent or invalid
   * @param contentPartTypes immutable ordered part types; missing or invalid types use
   *     {@code MISSING_CONTENT_TYPE}
   */
  record Item(
      String id,
      int outputIndex,
      String type,
      String role,
      String status,
      List<String> contentPartTypes) {}

  /**
   * Correlation address from an audio, transcript or content-part event.
   *
   * @param responseId sanitized response ID, or {@code null} if absent or invalid
   * @param itemId sanitized item ID, or {@code null} if absent or invalid
   * @param outputIndex zero-based output-item index, or {@code -1} if absent or invalid
   * @param contentIndex zero-based content-part index, or {@code -1} if absent or invalid
   */
  record Coordinate(String responseId, String itemId, int outputIndex, int contentIndex) {}

  private static final Set<String> ACTIONABLE =
      Set.of(
          "function_call",
          "function_call_output",
          "tool_call",
          "tool_call_output",
          "mcp_call",
          "mcp_list_tools",
          "mcp_approval_request",
          "mcp_approval_response");
  private final Map<String, Long> events = new LinkedHashMap<>();
  private final Set<String> responseIds = new LinkedHashSet<>(),
      audioIds = new LinkedHashSet<>(),
      transcriptIds = new LinkedHashSet<>(),
      unexpectedTypes = new LinkedHashSet<>();
  private final Set<Coordinate> audioCoordinates = new LinkedHashSet<>(),
      transcriptCoordinates = new LinkedHashSet<>(),
      otherCoordinates = new LinkedHashSet<>();
  private final Map<Integer, Item> observedItems = new LinkedHashMap<>();
  private final Map<Integer, Item> completedItems = new LinkedHashMap<>();
  private final Set<String> observedAudioItems = new LinkedHashSet<>();
  private List<Item> items = List.of();
  private String responseId, finalStatus, audioItemId, validationCode;
  private Integer outputCount;
  private boolean malformedCorrelation, actionable, unknownSchema;

  /**
   * Records an event's structural evidence and builds its payload-free journal entry.
   *
   * <p>Streaming part types {@code audio}/{@code text} and final item part types
   * {@code output_audio}/{@code output_text} are distinct schemas. Unsupported item or part types,
   * missing event types and malformed correlation are recorded for later validation. Other event
   * types are generally counted without schema validation, though function-call and MCP event
   * prefixes flag actionable output. A {@code response.done} event supplies the final item list.
   *
   * <p>This method does not validate the accumulated response or throw for ordinary missing JSON
   * fields. Validation remains explicit through {@link #validate()}.
   *
   * @param event non-null parsed provider event; payload fields are not retained
   * @return a new mutable map of selected structural fields, including {@code null} for missing or
   *     invalid tokens; changing the map does not change this collector
   */
  synchronized Map<String, Object> observe(JsonNode event) {
    String type = token(event.path("type"));
    events.merge(type == null ? "INVALID_EVENT_TYPE" : type, 1L, Long::sum);
    var safe = new LinkedHashMap<String, Object>();
    safe.put("eventType", type);
    safe.put("responseId", token(event.path("response_id")));
    safe.put("itemId", token(event.path("item_id")));
    safe.put("outputIndex", index(event.path("output_index")));
    safe.put("contentIndex", index(event.path("content_index")));
    if (type == null) {
      unknownSchema = true;
      return safe;
    }
    if (type.startsWith("response.function_call")
        || type.startsWith("response.mcp_")
        || type.startsWith("mcp_")) actionable = true;
    if (type.equals("response.created")) {
      String id = token(event.path("response").path("id"));
      if (id == null) malformedCorrelation = true;
      else {
        responseIds.add(id);
        if (responseId == null) responseId = id;
      }
      safe.put("responseId", id);
    }
    if (type.equals("response.output_item.added") || type.equals("response.output_item.done")) {
      int outputIndex = index(event.path("output_index"));
      var item = item(event.path("item"), outputIndex);
      Item previous = observedItems.put(outputIndex, item);
      if (previous != null
          && (!Objects.equals(previous.id(), item.id())
              || !Objects.equals(previous.type(), item.type())
              || !Objects.equals(previous.role(), item.role()))) malformedCorrelation = true;
      if (type.equals("response.output_item.done")) completedItems.put(outputIndex, item);
      if (item.contentPartTypes().contains("output_audio") && item.id() != null)
        observedAudioItems.add(item.id());
      inspectType(item.type());
      safe.put("item", item);
      String id = token(event.path("response_id"));
      if (id == null || outputIndex < 0) malformedCorrelation = true;
      else responseIds.add(id);
    }
    if (type.equals("response.output_audio.delta") || type.equals("response.output_audio.done")) {
      audioCoordinates.add(coordinate(event));
      if (type.endsWith(".delta")) addId(audioIds, event.path("item_id"));
    }
    if (type.equals("response.output_audio_transcript.delta")
        || type.equals("response.output_audio_transcript.done")) {
      transcriptCoordinates.add(coordinate(event));
      if (type.endsWith(".delta")) addId(transcriptIds, event.path("item_id"));
    }
    if (type.startsWith("response.output_text.")) otherCoordinates.add(coordinate(event));
    if (type.equals("response.content_part.added") || type.equals("response.content_part.done")) {
      var coordinate = coordinate(event);
      String part = token(event.path("part").path("type"));
      safe.put("contentPartType", part);
      // Streaming part types differ from the final message content schema.
      if ("audio".equals(part)) audioCoordinates.add(coordinate);
      else if ("text".equals(part)) otherCoordinates.add(coordinate);
      else {
        unknownSchema = true;
        unexpectedTypes.add(part == null ? "MISSING_CONTENT_TYPE" : part);
      }
    }
    if (type.equals("response.done")) {
      var response = event.path("response");
      responseId = token(response.path("id"));
      finalStatus = token(response.path("status"));
      if (responseId == null) malformedCorrelation = true;
      else responseIds.add(responseId);
      var output = response.path("output");
      if (!output.isArray()) unknownSchema = true;
      else {
        outputCount = output.size();
        var summaries = new ArrayList<Item>();
        for (int i = 0; i < output.size(); i++) {
          var item = item(output.get(i), i);
          summaries.add(item);
          inspectType(item.type());
          if (item.contentPartTypes().contains("output_audio")) {
            if (audioItemId == null) audioItemId = item.id();
          }
        }
        items = List.copyOf(summaries);
        var audioItems =
            items.stream().filter(i -> i.contentPartTypes().contains("output_audio")).toList();
        audioItemId = audioItems.size() == 1 ? audioItems.getFirst().id() : null;
      }
      safe.put("responseId", responseId);
      safe.put("finalStatus", finalStatus);
      safe.put("outputItemCount", outputCount);
      safe.put("outputItems", items);
    }
    return safe;
  }

  /**
   * Evaluates the evidence collected so far and stores the result for subsequent snapshots.
   *
   * <p>Unsupported schemas take precedence over an incomplete final status, which takes precedence
   * over correlation and output-shape failures. Both audio and transcript delta item IDs are
   * required. Separate {@code .done} events and streaming item events are not required, but their
   * recorded evidence is checked when present. Text output, actionable items, duplicate final item
   * IDs and conflicting response or item coordinates cannot pass.
   *
   * <p>The speech adapter treats {@code PROVIDER_SCHEMA_UNSUPPORTED} as non-retryable and makes
   * {@code UNEXPECTED_AUDIO_STRUCTURE} retryable only after session configuration was validated.
   * This method only returns the classification; it does not schedule another attempt.
   *
   * @return {@code null} if the collected structure passes, otherwise
   *     {@code PROVIDER_SCHEMA_UNSUPPORTED}, {@code INCOMPLETE_AUDIO} or
   *     {@code UNEXPECTED_AUDIO_STRUCTURE}
   */
  synchronized String validate() {
    validationCode = validateShape();
    return validationCode;
  }

  private String validateShape() {
    if (unknownSchema) return "PROVIDER_SCHEMA_UNSUPPORTED";
    if (!"completed".equals(finalStatus)) return "INCOMPLETE_AUDIO";
    if (actionable
        || malformedCorrelation
        || responseIds.size() != 1
        || !responseIds.contains(responseId)) return "UNEXPECTED_AUDIO_STRUCTURE";
    var audioItems =
        items.stream().filter(i -> i.contentPartTypes().contains("output_audio")).toList();
    if (audioItems.size() != 1 || audioIds.size() != 1 || transcriptIds.size() != 1)
      return "UNEXPECTED_AUDIO_STRUCTURE";
    var audioItem = audioItems.getFirst();
    var ids = new HashSet<String>();
    for (Item item : items) {
      if (item.id() == null
          || !ids.add(item.id())
          || !"message".equals(item.type())
          || !"assistant".equals(item.role())
          || !"completed".equals(item.status())) return "UNEXPECTED_AUDIO_STRUCTURE";
      // Only an empty completed assistant envelope is harmless alongside the audio message.
      if (item != audioItem && !item.contentPartTypes().isEmpty())
        return "UNEXPECTED_AUDIO_STRUCTURE";
    }
    if (!audioItem.contentPartTypes().equals(List.of("output_audio"))
        || !audioIds.contains(audioItem.id())
        || !transcriptIds.contains(audioItem.id())
        || !otherCoordinates.isEmpty()) return "UNEXPECTED_AUDIO_STRUCTURE";
    for (var coordinates : List.of(audioCoordinates, transcriptCoordinates)) {
      if (coordinates.isEmpty()) return "UNEXPECTED_AUDIO_STRUCTURE";
      for (var c : coordinates)
        if (!Objects.equals(responseId, c.responseId())
            || !Objects.equals(audioItem.id(), c.itemId())
            || c.outputIndex() != audioItem.outputIndex()
            || c.contentIndex() != 0) return "UNEXPECTED_AUDIO_STRUCTURE";
    }
    if (!observedAudioItems.isEmpty() && !observedAudioItems.equals(Set.of(audioItem.id())))
      return "UNEXPECTED_AUDIO_STRUCTURE";
    for (var entry : completedItems.entrySet()) {
      if (entry.getKey() < 0 || entry.getKey() >= items.size()) return "UNEXPECTED_AUDIO_STRUCTURE";
      Item completed = entry.getValue(), finished = items.get(entry.getKey());
      if (!"completed".equals(completed.status())
          || !completed.contentPartTypes().equals(finished.contentPartTypes()))
        return "UNEXPECTED_AUDIO_STRUCTURE";
    }
    for (var entry : observedItems.entrySet()) {
      if (entry.getKey() < 0 || entry.getKey() >= items.size()) return "UNEXPECTED_AUDIO_STRUCTURE";
      Item observed = entry.getValue(), finished = items.get(entry.getKey());
      if (!Objects.equals(observed.id(), finished.id())
          || !Objects.equals(observed.type(), finished.type())
          || !Objects.equals(observed.role(), finished.role())) return "UNEXPECTED_AUDIO_STRUCTURE";
    }
    return null;
  }

  /**
   * Copies the current structural diagnostics for the attempt's final sidecar.
   *
   * <p>No validation is performed here. {@code validationCode} is the last result of
   * {@link #validate()}; {@code null} can mean either acceptance or that validation has not run.
   * Other fields may be empty or {@code null} when the stream ended before a final response.
   *
   * @return a new mutable map with copied collections and payload-free item summaries; the caller
   *     may add attempt-level diagnostics without changing this collector
   */
  synchronized Map<String, Object> snapshot() {
    var safe = new LinkedHashMap<String, Object>();
    safe.put("responseId", responseId);
    safe.put("finalResponseStatus", finalStatus);
    safe.put("outputItemCount", outputCount);
    safe.put("outputItems", items);
    safe.put("observedOutputItems", List.copyOf(observedItems.values()));
    safe.put("observedAudioItemIds", List.copyOf(observedAudioItems));
    safe.put("correlationInvalid", malformedCorrelation);
    safe.put("audioBearingItemId", audioItemId);
    safe.put("audioDeltaItemIds", List.copyOf(audioIds));
    safe.put("transcriptDeltaItemIds", List.copyOf(transcriptIds));
    safe.put("responseIds", List.copyOf(responseIds));
    safe.put("audioCoordinates", List.copyOf(audioCoordinates));
    safe.put("transcriptCoordinates", List.copyOf(transcriptCoordinates));
    safe.put("unexpectedItemTypes", List.copyOf(unexpectedTypes));
    safe.put("providerEventCounts", Map.copyOf(events));
    safe.put("validationCode", validationCode);
    return safe;
  }

  private Coordinate coordinate(JsonNode e) {
    var c =
        new Coordinate(
            token(e.path("response_id")),
            token(e.path("item_id")),
            index(e.path("output_index")),
            index(e.path("content_index")));
    if (c.responseId() == null || c.itemId() == null || c.outputIndex() < 0 || c.contentIndex() < 0)
      malformedCorrelation = true;
    if (c.responseId() != null) {
      responseIds.add(c.responseId());
      if (responseId == null) responseId = c.responseId();
    }
    return c;
  }

  private void addId(Set<String> ids, JsonNode node) {
    String id = token(node);
    if (id != null) ids.add(id);
  }

  private Item item(JsonNode node, int index) {
    var parts = new ArrayList<String>();
    if (!node.isObject() || !node.path("content").isArray()) {
      if ("message".equals(token(node.path("type")))) malformedCorrelation = true;
    } else
      for (var part : node.path("content")) {
        String type = token(part.path("type"));
        parts.add(type == null ? "MISSING_CONTENT_TYPE" : type);
        if (!Set.of("output_audio", "output_text").contains(Objects.toString(type, ""))) {
          unexpectedTypes.add(type == null ? "MISSING_CONTENT_TYPE" : type);
          unknownSchema = true;
        }
      }
    return new Item(
        token(node.path("id")),
        index,
        token(node.path("type")),
        token(node.path("role")),
        token(node.path("status")),
        List.copyOf(parts));
  }

  private void inspectType(String type) {
    if (ACTIONABLE.contains(Objects.toString(type, ""))) {
      actionable = true;
      unexpectedTypes.add(type);
    } else if (!"message".equals(type)) {
      unknownSchema = true;
      unexpectedTypes.add(type == null ? "MISSING_ITEM_TYPE" : type);
    }
  }

  private static String token(JsonNode value) {
    return value.isTextual() && value.asText().matches("[A-Za-z0-9_.-]{1,200}")
        ? value.asText()
        : null;
  }

  private static int index(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0
        ? value.asInt()
        : -1;
  }
}
