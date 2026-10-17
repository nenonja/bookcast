package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.library.adapter.JdbcLibraryStore;
import com.bookcast.app.library.application.LibraryService;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class LegacyLibraryMigrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @TempDir Path directory;

  @Test
  void populatedV2MigratesWithoutGuessingOrChangingPaidEvidence() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(MigrationVersion.fromVersion("2"))
        .load()
        .migrate();
    var db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    UUID book1 = UUID.randomUUID(),
        book2 = UUID.randomUUID(),
        chapter = UUID.randomUUID(),
        chapter2 = UUID.randomUUID(),
        revision = UUID.randomUUID(),
        block = UUID.randomUUID(),
        job = UUID.randomUUID();
    db.update(
        "insert into books values (?,?),(?,?)", book1, "Ambiguous title", book2, "Ambiguous title");
    db.update(
        "insert into chapters(id,book_id,title) values (?,?,?),(?,?,?)",
        chapter,
        book1,
        "Chapter 2?",
        chapter2,
        book2,
        "Chapter 2?");
    db.update(
        "insert into source_revisions(id,chapter_id,source_language,content_hash) values (?,?,?,?)",
        revision,
        chapter,
        "en",
        "frozen-source-hash");
    db.update(
        "insert into source_blocks values (?,?,?,?,?)", block, revision, 0, "Frozen source.", null);
    Path audio = directory.resolve("paid-final.m4a");
    byte[] bytes = {1, 2, 3, 4};
    Files.write(audio, bytes);
    db.update(
        "insert into generations(id,request_id,chapter_id,revision_id,status,stage,generation_model,audio_model,audio_path) values (?,?,?,?,?,?,?,?,?)",
        job,
        UUID.randomUUID(),
        chapter,
        revision,
        "succeeded",
        "complete",
        "gpt-5.4",
        "gpt-realtime-2.1-mini",
        audio.toString());
    db.update("insert into script_segments values (?,0,?::jsonb)", job, "{\"frozenScript\":true}");
    db.update(
        "insert into audio_groups values (?,0,?::jsonb)", job, "{\"hash\":\"frozen-audio-hash\"}");
    db.update(
        "insert into generation_attempts values (?,?,?::jsonb)",
        UUID.randomUUID(),
        job,
        "{\"usage\":{\"outputTokens\":100}}");
    var preserved = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "books",
            "source_revisions",
            "source_blocks",
            "generations",
            "script_segments",
            "audio_groups",
            "generation_attempts"))
      preserved.put(table, db.queryForList("select * from " + table + " order by 1"));
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from chapters where chapter_number is null", Integer.class));
    assertEquals(
        book1, db.queryForObject("select book_id from chapters where id=?", UUID.class, chapter));
    assertEquals(
        book2, db.queryForObject("select book_id from chapters where id=?", UUID.class, chapter2));
    var library = new LibraryService(new JdbcLibraryStore(db));
    var before = library.chapter(chapter);
    var target = library.createBook("Correct owner-selected book");
    library.moveChapter(chapter, target.id(), 2);
    var after = library.chapter(chapter);
    assertEquals(before.id(), after.id());
    assertEquals(before.sourceRevisionId(), after.sourceRevisionId());
    assertEquals(before.blocks(), after.blocks());
    assertEquals(before.sourceHash(), after.sourceHash());
    assertEquals(2, after.chapterNumber());
    assertEquals(target.id(), after.bookId());
    for (var entry : preserved.entrySet()) {
      var actual = db.queryForList("select * from " + entry.getKey() + " order by 1");
      if (entry.getKey().equals("books")) assertTrue(actual.containsAll(entry.getValue()));
      else assertEquals(entry.getValue(), actual, entry.getKey());
    }
    assertArrayEquals(bytes, Files.readAllBytes(audio));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("update source_blocks set content='changed' where id=?", block));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("delete from source_revisions where id=?", revision));
  }
}
