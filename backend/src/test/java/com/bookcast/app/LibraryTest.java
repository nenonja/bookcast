package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.library.*;
import com.bookcast.app.library.application.*;
import com.bookcast.app.library.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryTest {
  @Test
  void bookCreationValidatesAndNormalizesTitle() {
    var store = mock(LibraryStore.class);
    var service = new LibraryService(store);
    var book = new Book(UUID.randomUUID(), "Kirja");
    when(store.createBook("Kirja")).thenReturn(book);
    assertSame(book, service.createBook(" Kirja "));
    for (String title : List.of("", " ", "x".repeat(201)))
      assertThrows(LibraryProblem.class, () -> service.createBook(title));
    verify(store, times(1)).createBook(any());
  }

  @Test
  void placementRequiresBookAndPositiveNumber() {
    UUID book = UUID.randomUUID();
    assertEquals(2, new ChapterPlacement(book, 2).chapterNumber());
    for (Integer number : Arrays.asList(null, 0, -1)) {
      var e = assertThrows(LibraryProblem.class, () -> new ChapterPlacement(book, number));
      assertEquals("INVALID_CHAPTER_NUMBER", e.getMessage());
    }
    assertEquals(
        "BOOK_REQUIRED",
        assertThrows(LibraryProblem.class, () -> new ChapterPlacement(null, 1)).getMessage());
  }

  @Test
  void applicationChecksDuplicateBeforeCreatingAnySource() {
    var store = mock(LibraryStore.class);
    var service = new LibraryService(store);
    UUID book = UUID.randomUUID();
    when(store.book(book)).thenReturn(new Book(book, "Book"));
    when(store.numberTaken(book, 2, null)).thenReturn(true);
    assertEquals(
        "CHAPTER_NUMBER_CONFLICT",
        assertThrows(
                LibraryProblem.class,
                () -> service.importChapter(book, 2, "Chapter", "A useful paragraph.", "en"))
            .getMessage());
    verify(store, never()).chapter(any(), any(), any(), anyInt());
    verify(store, never()).append(any(), any(), any(), any(), any());
  }
}
