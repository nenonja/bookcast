package com.bookcast.app.generation.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.domain.SpeechChunkPlanner;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** Frozen, synthetic payloads using the JSON contract from before the Java terminology rename. */
@JsonTest
class GenerationJsonCompatibilityTest {
  @Autowired ObjectMapper json;
  @TempDir Path data;

  static Stream<Arguments> payloads() {
    return Stream.of(
        Arguments.of("legacy-dialogue-segment.json", DialogueSegment.class),
        Arguments.of("legacy-audio-receipt.json", ValidatedAudioChunk.class),
        Arguments.of("legacy-provider-attempt.json", ProviderAttempt.class),
        Arguments.of("legacy-minimal-attempt.json", ProviderAttempt.class));
  }

  static String fixture(String name) throws Exception {
    try (var input = GenerationJsonCompatibilityTest.class.getResourceAsStream("/generation/" + name)) {
      assertNotNull(input, name);
      return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @ParameterizedTest
  @MethodSource("payloads")
  void oldPayloadDeserializesAndWritesTheSameJsonShape(String name, Class<?> type) throws Exception {
    String legacy = fixture(name);
    Object value = json.readValue(legacy, type);
    assertEquals(json.readTree(legacy), json.readTree(json.writeValueAsString(value)));
    if (value instanceof ProviderAttempt attempt && name.equals("legacy-minimal-attempt.json")) {
      assertNull(attempt.estimatedCost());
      assertNull(attempt.attemptNumber());
      assertFalse(attempt.usage().containsKey("outputTokens"));
    }
  }

  @Test
  void oldDialogueRetainsHashesGlobalOrderAndSegmentLocalSpans() throws Exception {
    var segment = json.readValue(fixture("legacy-dialogue-segment.json"), DialogueSegment.class);
    var continuation = new DialogueSegment(1, List.of(UUID.fromString("00000000-0000-4000-8000-000000000004")),
        List.of(new DialogueTurn(Speaker.HOST, "Jatketaan."), new DialogueTurn(Speaker.EXPERT, "Seuraava asia.")));
    var chunks = SpeechChunkPlanner.plan(List.of(segment, continuation));
    assertEquals(List.of(0, 1, 2, 3, 4), chunks.stream().map(SpeechChunk::order).toList());
    assertEquals(List.of(0, 0, 0, 1, 1), chunks.stream().map(SpeechChunk::segment).toList());
    assertEquals(List.of(Speaker.HOST, Speaker.EXPERT, Speaker.HOST, Speaker.HOST, Speaker.EXPERT),
        chunks.stream().map(SpeechChunk::speaker).toList());
    assertEquals("Lähde jaetaan osiin.\n\nValmiit osat säilyvät.", chunks.get(1).text());
    assertEquals(List.of(new TurnSpan(1, 0, 20), new TurnSpan(2, 0, 22)), chunks.get(1).slices());
    assertEquals(List.of(
        "06eb14d566967a4ebf169add5abaf10db9f631c98ee3ae78ad58ec409280c11b",
        "c3d4ad37cb694f5a386b3863a0280c32bba435062f6cb76e4b0c8689ceefc6f7",
        "1d1ae1b4d8bed33dc6c74d87aea1139aa7e0b30aa21bf81095891f7557f84baf"),
        chunks.subList(0, 3).stream().map(SpeechChunk::textHash).toList());
    assertEquals(chunks.getFirst(), json.readValue(fixture("legacy-audio-receipt.json"), ValidatedAudioChunk.class).group());
  }

  @Test
  void oldReceiptStillVerifiesItsOriginalPcmPathAndHash() throws Exception {
    var receipt = json.readValue(fixture("legacy-audio-receipt.json"), ValidatedAudioChunk.class);
    var pcm = ByteBuffer.allocate(24000 * 2 * 6).order(ByteOrder.LITTLE_ENDIAN);
    while (pcm.hasRemaining()) pcm.putShort((short) 1800);
    Path path = data.resolve(receipt.path());
    Files.createDirectories(path.getParent());
    Files.write(path, pcm.array());
    var artifacts = new LocalAudioArtifacts(data.toString(), 30);
    artifacts.verify(receipt);
    assertEquals(path.toAbsolutePath().toString(), artifacts.absolutePath(receipt.path()));
    assertEquals(receipt, artifacts.validate(receipt.group(), receipt.path(), receipt.transcript()));
    // Technical acceptance deliberately does not establish that the transcript matches the script.
    var deviation = artifacts.validate(receipt.group(), receipt.path(), "Eri sisältö.");
    assertTrue(deviation.lexicalDeviation());
    assertEquals("Eri sisältö.", deviation.transcript());
    assertEquals(receipt.hash(), deviation.hash());
  }

  @Test
  void assemblyRejectsAnOrderGapBeforeEncoding() throws Exception {
    var receipt = json.readValue(fixture("legacy-audio-receipt.json"), ValidatedAudioChunk.class);
    var chunk = receipt.group();
    var outOfOrder = new SpeechChunk(1, chunk.segment(), chunk.speaker(), chunk.text(), chunk.textHash(), chunk.slices());
    var invalid = new ValidatedAudioChunk(outOfOrder, receipt.path(), receipt.hash(), receipt.transcript(),
        receipt.lexicalDeviation(), receipt.duration(), receipt.longestInternalSilence(),
        receipt.trailingSilence(), receipt.wordsPerMinute());
    var artifacts = new LocalAudioArtifacts(data.toString(), 30);
    var error = assertThrows(IllegalStateException.class,
        () -> artifacts.assemble(UUID.randomUUID(), List.of(invalid),
            new com.bookcast.app.generation.application.Capabilities.AudioMetadata("Book", "Chapter", 1)));
    assertEquals("ASSEMBLY_ORDER", error.getMessage());
  }
}
