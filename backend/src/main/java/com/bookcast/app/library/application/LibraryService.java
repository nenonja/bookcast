package com.bookcast.app.library.application;

import com.bookcast.app.library.*;
import com.bookcast.app.library.domain.*;
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
      UUID bookId, Integer chapterNumber, String title, String text, String language) {
    title = title(title);
    var placement = new ChapterPlacement(bookId, chapterNumber);
    requireAvailable(placement, null);
    var blocks = SourceNormalizer.blocks(text);
    UUID id = UUID.randomUUID();
    store.chapter(id, bookId, title, chapterNumber);
    return store.append(
        id, UUID.randomUUID(), language(language), SourceNormalizer.hash(text), blocks);
  }

  @Transactional
  public Book createBook(String title) {
    return store.createBook(title(title));
  }

  public List<Book> books() {
    return store.books();
  }

  public Book book(UUID id) {
    return store.book(id);
  }

  public List<ChapterSource> chapters(UUID bookId) {
    store.book(bookId);
    return store.list(bookId);
  }

  @Transactional
  public ChapterSource moveChapter(UUID chapterId, UUID bookId, Integer number) {
    var placement = new ChapterPlacement(bookId, number);
    store.read(chapterId, null);
    requireAvailable(placement, chapterId);
    store.move(chapterId, bookId, number);
    return store.read(chapterId, null);
  }

  private void requireAvailable(ChapterPlacement placement, UUID excluding) {
    store.book(placement.bookId());
    if (store.numberTaken(placement.bookId(), placement.chapterNumber(), excluding))
      throw new LibraryProblem("CHAPTER_NUMBER_CONFLICT");
  }

  private String title(String value) {
    if (value == null || value.isBlank() || value.strip().length() > 200)
      throw new LibraryProblem("TITLE_REQUIRED");
    return value.strip();
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
