package com.bookcast.app.library.application;

import com.bookcast.app.library.*;
import java.util.*;

public interface LibraryStore {
  Book createBook(String title);

  Book book(UUID id);

  List<Book> books();

  boolean numberTaken(UUID bookId, int number, UUID excludingChapter);

  void move(UUID chapterId, UUID bookId, int number);

  List<ChapterSource> list(UUID bookId);

  void chapter(UUID id, UUID bookId, String title, int number);

  ChapterSource append(
      UUID chapterId,
      UUID revisionId,
      String language,
      String hash,
      List<ChapterSource.Block> blocks);

  ChapterSource read(UUID chapterId, UUID revisionId);

  List<ChapterSource> list();
}
