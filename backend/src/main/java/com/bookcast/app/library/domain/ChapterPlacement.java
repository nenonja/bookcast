package com.bookcast.app.library.domain;

import java.util.UUID;

/**
 * A numbered chapter belongs to exactly one book. Legacy unnumbered rows are read-only snapshots.
 */
public record ChapterPlacement(UUID bookId, Integer chapterNumber) {
  public ChapterPlacement {
    if (bookId == null) throw new LibraryProblem("BOOK_REQUIRED");
    if (chapterNumber == null || chapterNumber <= 0)
      throw new LibraryProblem("INVALID_CHAPTER_NUMBER");
  }
}
