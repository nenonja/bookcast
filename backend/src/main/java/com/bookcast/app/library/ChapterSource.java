package com.bookcast.app.library;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ChapterSource(
    UUID id,
    UUID bookId,
    String bookTitle,
    String title,
    UUID sourceRevisionId,
    String sourceLanguage,
    String sourceHash,
    OffsetDateTime createdAt,
    List<Block> blocks) {
  public ChapterSource {
    blocks = List.copyOf(blocks);
  }

  public record Block(UUID id, int order, String text, String excludedReason) {}
}
