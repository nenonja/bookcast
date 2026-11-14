package com.bookcast.app.generation.domain;

import com.bookcast.app.generation.domain.Podcast.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Partitions ordered dialogue into bounded, single-speaker speech requests without losing text.
 * Consecutive turns may share a chunk only within one segment and the existing UTF-8 byte budget.
 * The existing SHA-256 helpers also remain here for persisted text and PCM verification.
 */
public final class SpeechChunkPlanner {
  private SpeechChunkPlanner() {}

  public static List<SpeechChunk> plan(List<DialogueSegment> segments) {
    List<SpeechChunk> result = new ArrayList<>();
    for (var s : segments) {
      for (int t = 0; t < s.dialogueTurns().size(); t++) {
        var turn = s.dialogueTurns().get(t);
        int start = 0;
        while (start < turn.text().length()) {
          int end = start;
          while (end < turn.text().length()
              && turn.text().substring(start, end + 1).getBytes(StandardCharsets.UTF_8).length
                  <= 1400) end++;
          if (end < turn.text().length()) {
            int split = turn.text().lastIndexOf(' ', end - 1);
            if (split > start) end = split + 1;
            else if (Character.isHighSurrogate(turn.text().charAt(end - 1))) end--;
          }
          String text = turn.text().substring(start, end);
          var slice = new TurnSpan(t, start, end);
          if (!result.isEmpty()) {
            var old = result.getLast();
            String combined = old.text() + "\n\n" + text;
            if (old.segment() == s.order()
                && old.speaker() == turn.speakerRole()
                && combined.getBytes(StandardCharsets.UTF_8).length <= 1400) {
              var slices = new ArrayList<>(old.slices());
              slices.add(slice);
              result.set(
                  result.size() - 1,
                  new SpeechChunk(
                      old.order(), old.segment(), old.speaker(), combined, hash(combined), slices));
              start = end;
              continue;
            }
          }
          result.add(
              new SpeechChunk(
                  result.size(), s.order(), turn.speakerRole(), text, hash(text), List.of(slice)));
          start = end;
        }
      }
    }
    return List.copyOf(result);
  }

  public static String hash(String text) {
    return hash(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("HASH_FAILURE");
    }
  }
}
