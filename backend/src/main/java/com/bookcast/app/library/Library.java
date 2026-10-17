package com.bookcast.app.library;

import java.util.List;
import java.util.UUID;

public interface Library {
  ChapterSource importChapter(
      UUID bookId, Integer chapterNumber, String title, String text, String language);

  Book createBook(String title);

  List<Book> books();

  Book book(UUID id);

  List<ChapterSource> chapters(UUID bookId);

  ChapterSource moveChapter(UUID chapterId, UUID bookId, Integer chapterNumber);

  ChapterSource importRevision(UUID chapterId, String text, String language);

  ChapterSource chapter(UUID id);

  ChapterSource revision(UUID chapterId, UUID revisionId);

  List<ChapterSource> chapters();
}
