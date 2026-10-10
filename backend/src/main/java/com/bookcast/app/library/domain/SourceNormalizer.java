package com.bookcast.app.library.domain;

import com.bookcast.app.library.ChapterSource.Block;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class SourceNormalizer {
  private SourceNormalizer() {}

  public static List<Block> blocks(String source) {
    if (source == null || source.isBlank() || source.length() > 200000)
      throw new IllegalArgumentException("SOURCE_SIZE");
    List<Block> result = new ArrayList<>();
    for (String paragraph :
        source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\uFEFF", "")
            .split("\n[\t ]*\n+")) {
      String text = paragraph.strip();
      if (text.isEmpty()) continue;
      // Bound oversized paragraphs while retaining order and content. IDs are revision-scoped
      // UUIDs.
      while (text.length() > 6000) {
        int split = text.lastIndexOf(' ', 6000);
        if (split < 1) split = 6000;
        String part = text.substring(0, split);
        result.add(new Block(UUID.randomUUID(), result.size(), part, noise(part)));
        text = text.substring(split).stripLeading();
      }
      if (!text.isBlank())
        result.add(new Block(UUID.randomUUID(), result.size(), text, noise(text)));
    }
    if (result.stream().allMatch(b -> b.excludedReason() != null))
      throw new IllegalArgumentException("NO_LEARNING_CONTENT");
    return List.copyOf(result);
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("HASH_FAILURE");
    }
  }

  private static String noise(String text) {
    String t = text.toLowerCase(Locale.ROOT);
    if (t.equals("talk")
        || t.equals("type")
        || t.startsWith("livebook features:")
        || (t.startsWith("recap ") && t.endsWith(" to the ai assistant"))
        || (t.startsWith("you can automatically highlight") && t.contains("disable quick notes"))
        || (t.startsWith("ask a question, share an example") && t.contains("discussion icon")))
      return "acquisition_control";
    return null;
  }
}
