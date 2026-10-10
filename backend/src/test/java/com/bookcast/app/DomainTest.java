package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.adapter.LocalAudioArtifacts;
import com.bookcast.app.generation.domain.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.domain.SourceNormalizer;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainTest {
  @Test
  void orderedBlocksAndAuditableNoise() {
    var blocks = SourceNormalizer.blocks("Actual content.\n\nTalk\n\nSecond paragraph.");
    assertEquals(3, blocks.size());
    assertEquals("acquisition_control", blocks.get(1).excludedReason());
    assertNotEquals(blocks.get(0).id(), blocks.get(2).id());
    assertThrows(UnsupportedOperationException.class, () -> blocks.clear());
    assertThrows(IllegalArgumentException.class, () -> SourceNormalizer.blocks("Talk"));
  }

  @Test
  void audioPlanRetainsEveryTurnSliceExactlyOnce() {
    String text = "Selkeä suomalainen selitys. ".repeat(150);
    var segment =
        new Segment(
            0,
            List.of(UUID.randomUUID()),
            List.of(
                new Turn(Speaker.EXPERT, text),
                new Turn(Speaker.HOST, "Miksi?"),
                new Turn(Speaker.EXPERT, "Näin asia toimii.")));
    var groups = AudioGroups.plan(List.of(segment));
    for (int turn = 0; turn < 3; turn++) {
      final int index = turn;
      var slices =
          groups.stream().flatMap(g -> g.slices().stream()).filter(s -> s.turn() == index).toList();
      int offset = 0;
      StringBuilder reconstructed = new StringBuilder();
      for (var slice : slices) {
        assertEquals(offset, slice.start());
        reconstructed.append(segment.dialogueTurns().get(turn).text(), slice.start(), slice.end());
        offset = slice.end();
      }
      assertEquals(segment.dialogueTurns().get(turn).text(), reconstructed.toString());
    }
    assertTrue(
        groups.stream()
            .allMatch(
                g -> g.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1400));
  }

  @Test
  void providerTrailingSilenceDoesNotPassEvenWithFullTranscript() {
    var group = new Group(0, 0, Speaker.EXPERT, "Tämä on kokonainen ajatus.", "hash", List.of());
    var buffer = ByteBuffer.allocate(24000 * 2 * 10).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < 24000 * 5; i++) buffer.putShort((short) 1800);
    assertThrows(
        IllegalArgumentException.class,
        () -> LocalAudioArtifacts.waveform(group, buffer.array(), "test", group.text()));
  }

  @Test
  void malformedCoverageCannotPass() {
    var segment =
        new Segment(
            0,
            List.of(UUID.randomUUID()),
            List.of(new Turn(Speaker.HOST, "Miksi?"), new Turn(Speaker.EXPERT, "Siksi.")));
    assertThrows(
        IllegalArgumentException.class, () -> segment.validate(0, List.of(UUID.randomUUID())));
  }
}
