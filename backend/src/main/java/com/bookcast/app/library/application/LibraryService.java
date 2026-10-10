package com.bookcast.app.library.application;

import com.bookcast.app.library.*;
import com.bookcast.app.library.domain.SourceNormalizer;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryService implements Library {
  private final LibraryStore store;

  public LibraryService(LibraryStore store) {
    this.store = store;
  }

  @Transactional
  public ChapterSource importChapter(
      UUID bookId, String bookTitle, String title, String text, String language) {
    if (title == null || title.isBlank() || title.length() > 200)
      throw new IllegalArgumentException("TITLE_REQUIRED");
    var blocks = SourceNormalizer.blocks(text);
    UUID book = store.book(bookId, bookTitle == null || bookTitle.isBlank() ? title : bookTitle);
    UUID id = UUID.randomUUID();
    store.chapter(id, book, title.strip());
    return store.append(
        id, UUID.randomUUID(), language(language), SourceNormalizer.hash(text), blocks);
  }

  @Transactional
  public ChapterSource importRevision(UUID id, String text, String language) {
    store.read(id, null);
    return store.append(
        id,
        UUID.randomUUID(),
        language(language),
        SourceNormalizer.hash(text),
        SourceNormalizer.blocks(text));
  }

  public ChapterSource chapter(UUID id) {
    return store.read(id, null);
  }

  public ChapterSource revision(UUID id, UUID revision) {
    return store.read(id, revision);
  }

  public List<ChapterSource> chapters() {
    return store.list();
  }

  private String language(String value) {
    String v = value == null ? "en" : value;
    if (!v.matches("[a-z]{2,3}(-[A-Za-z]{2,8})?"))
      throw new IllegalArgumentException("LANGUAGE_INVALID");
    return v;
  }
}
