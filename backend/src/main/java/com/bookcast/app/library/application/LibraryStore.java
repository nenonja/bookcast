package com.bookcast.app.library.application;

import com.bookcast.app.library.ChapterSource;
import java.util.*;

public interface LibraryStore {
  UUID book(UUID id, String title);

  void chapter(UUID id, UUID bookId, String title);

  ChapterSource append(
      UUID chapterId,
      UUID revisionId,
      String language,
      String hash,
      List<ChapterSource.Block> blocks);

  ChapterSource read(UUID chapterId, UUID revisionId);

  List<ChapterSource> list();
}
