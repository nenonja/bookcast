package com.bookcast.app.library.adapter;

import com.bookcast.app.library.*;
import com.bookcast.contract.api.LibraryApi;
import com.bookcast.contract.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LibraryController implements LibraryApi {
  private final Library library;
  private final ObjectMapper json;

  public LibraryController(Library library, ObjectMapper json) {
    this.library = library;
    this.json = json;
  }

  private ChapterDto dto(ChapterSource c) {
    return json.convertValue(c, ChapterDto.class);
  }

  public ResponseEntity<ChapterDto> importChapter(ImportChapterDto r) {
    return ResponseEntity.status(201)
        .body(
            dto(
                library.importChapter(
                    r.getBookId(),
                    r.getBookTitle(),
                    r.getTitle(),
                    r.getText(),
                    r.getSourceLanguage())));
  }

  public ResponseEntity<ChapterDto> importRevision(UUID id, ImportChapterDto r) {
    return ResponseEntity.status(201)
        .body(dto(library.importRevision(id, r.getText(), r.getSourceLanguage())));
  }

  public ResponseEntity<ChapterDto> getChapter(UUID id) {
    return ResponseEntity.ok(dto(library.chapter(id)));
  }

  public ResponseEntity<List<ChapterDto>> listChapters() {
    return ResponseEntity.ok(library.chapters().stream().map(this::dto).toList());
  }
}
