package com.bookcast.app.library;

import java.util.List;
import java.util.UUID;

public interface Library {
  ChapterSource importChapter(
      UUID bookId, String bookTitle, String title, String text, String language);

  ChapterSource importRevision(UUID chapterId, String text, String language);

  ChapterSource chapter(UUID id);

  ChapterSource revision(UUID chapterId, UUID revisionId);

  List<ChapterSource> chapters();
}
