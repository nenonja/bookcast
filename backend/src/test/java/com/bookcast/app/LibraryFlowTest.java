package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bookcast.app.generation.application.GenerationService;
import com.bookcast.app.library.Library;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {"bookcast.ai-mode=fake", "bookcast.worker-enabled=false"})
class LibraryFlowTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Library library;
  @Autowired GenerationService generation;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void booksNumberingOrderingMovesAndApiContract() throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/books")
                    .contentType("application/json")
                    .content("{\"title\":\" Kirja \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("Kirja"))
            .andReturn();
    UUID book =
        UUID.fromString(
            json.readTree(response.getResponse().getContentAsString()).path("id").asText());
    var other = library.createBook("Toinen");
    var ten = library.importChapter(book, 10, "Kymmenen", "Source with qualifications.", "en");
    var two = library.importChapter(book, 2, "Kaksi", "Source with an example.", "en");
    mvc.perform(get("/api/v1/books/" + book))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(book.toString()));
    mvc.perform(get("/api/v1/books/" + book + "/chapters"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].chapterNumber").value(2))
        .andExpect(jsonPath("$[1].chapterNumber").value(10));
    assertEquals(
        List.of(two.id(), ten.id()), library.chapters(book).stream().map(c -> c.id()).toList());
    library.importChapter(other.id(), 2, "Other two", "Another source.", "en");
    String body = "{\"chapterNumber\":2,\"title\":\"Duplicate\",\"text\":\"Source.\"}";
    mvc.perform(
            post("/api/v1/books/" + book + "/chapters")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHAPTER_NUMBER_CONFLICT"));
    for (String number : List.of("0", "-1", "null", "1.5"))
      mvc.perform(
              post("/api/v1/books/" + book + "/chapters")
                  .contentType("application/json")
                  .content(body.replace(":2", ":" + number)))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/books/" + book + "/chapters")
                .contentType("application/json")
                .content(body.replace(":2", ":3")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.bookId").value(book.toString()))
        .andExpect(jsonPath("$.chapterNumber").value(3));
    // Preserve old chapter URLs and the top-level import endpoint, now with explicit placement.
    mvc.perform(get("/api/v1/chapters/" + two.id())).andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/chapters")
                .contentType("application/json")
                .content(
                    "{\"bookId\":\""
                        + book
                        + "\",\"chapterNumber\":4,\"title\":\"Four\",\"text\":\"Source.\"}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/books").contentType("application/json").content("{\"title\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/books/" + UUID.randomUUID() + "/chapters"))
        .andExpect(status().isNotFound());

    var job = generation.start(ten.id(), ten.sourceRevisionId(), UUID.randomUUID());
    mvc.perform(
            put("/api/v1/chapters/" + ten.id() + "/placement")
                .contentType("application/json")
                .content("{\"bookId\":\"" + other.id() + "\",\"chapterNumber\":2}"))
        .andExpect(status().isConflict());
    assertEquals(book, library.chapter(ten.id()).bookId());
    mvc.perform(
            put("/api/v1/chapters/" + ten.id() + "/placement")
                .contentType("application/json")
                .content("{\"bookId\":\"" + other.id() + "\",\"chapterNumber\":7}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chapterNumber").value(7));
    var moved = library.chapter(ten.id());
    assertEquals(other.id(), moved.bookId());
    assertEquals(ten.sourceRevisionId(), moved.sourceRevisionId());
    assertEquals(ten.blocks(), moved.blocks());
    assertEquals(ten.sourceHash(), moved.sourceHash());
    assertEquals(ten.id(), generation.get(job.id()).chapterId());
    assertEquals(ten.sourceRevisionId(), generation.get(job.id()).sourceRevisionId());
    assertTrue(generation.attempts(job.id()).isEmpty());
    library.moveChapter(ten.id(), other.id(), 7); // idempotent organization
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update chapters set chapter_number=2 where id=?", ten.id()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update chapters set chapter_number=0 where id=?", ten.id()));
    assertEquals(7, library.chapter(ten.id()).chapterNumber());
    mvc.perform(get("/api/v1/books")).andExpect(status().isOk());
  }
}
