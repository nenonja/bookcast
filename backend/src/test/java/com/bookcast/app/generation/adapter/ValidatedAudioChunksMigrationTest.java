package com.bookcast.app.generation.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ValidatedAudioChunksMigrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @TempDir Path directory;

  Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(MigrationVersion.fromVersion(target)).load();
  }

  @Test
  void populatedV3RetainsRowsPayloadsAndDatabaseProtections() throws Exception {
    flyway("3").migrate();
    var db = new JdbcTemplate(new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    UUID book = UUID.randomUUID(), chapter = UUID.randomUUID(), revision = UUID.randomUUID();
    db.update("insert into books values (?,?)", book, "Säilytettävä kirja");
    db.update("insert into chapters(id,book_id,title,chapter_number) values (?,?,?,?)",
        chapter, book, "Lähdeluku", 6);
    db.update("insert into source_revisions(id,chapter_id,source_language,content_hash) values (?,?,?,?)",
        revision, chapter, "en", "frozen-source-hash");
    for (int i = 0; i < 3; i++)
      db.update("insert into source_blocks values (?,?,?,?,?)", UUID.randomUUID(), revision, i,
          "Source block " + i, i == 2 ? "acquisition-noise" : null);

    var json = new ObjectMapper().findAndRegisterModules();
    String script = GenerationJsonCompatibilityTest.fixture("legacy-dialogue-segment.json");
    String audio = GenerationJsonCompatibilityTest.fixture("legacy-audio-receipt.json");
    String attempt = GenerationJsonCompatibilityTest.fixture("legacy-provider-attempt.json");
    var receipt = json.readValue(audio, ValidatedAudioChunk.class);
    Path pcm = directory.resolve(receipt.path());
    Files.createDirectories(pcm.getParent());
    var buffer = java.nio.ByteBuffer.allocate(6 * 24000 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    while (buffer.hasRemaining()) buffer.putShort((short) 1800);
    byte[] pcmBytes = buffer.array();
    Files.write(pcm, pcmBytes);
    var artifacts = new LocalAudioArtifacts(directory.toString(), 30);
    artifacts.verify(receipt);
    Path m4a = directory.resolve("paid-final.m4a");
    byte[] m4aBytes = {5, 6, 7, 8};
    Files.write(m4a, m4aBytes);
    var pcmModified = Files.getLastModifiedTime(pcm);
    var m4aModified = Files.getLastModifiedTime(m4a);
    var jobs = new EnumMap<Status, UUID>(Status.class);
    for (Status status : Status.values()) {
      UUID job = UUID.randomUUID();
      jobs.put(status, job);
      // One active job per revision: failed/completed historical jobs may share it.
      UUID jobRevision = status == Status.queued ? UUID.randomUUID() : revision;
      db.update("""
          insert into generations(id,request_id,chapter_id,revision_id,status,stage,total_segments,
              total_audio,generation_model,audio_model,error_code,retryable,audio_path)
          values (?,?,?,?,?,?,1,3,'gpt-5.4','gpt-realtime-2.1-mini',?,?,?)
          """, job, UUID.randomUUID(), chapter, jobRevision, status.name(),
          status == Status.succeeded ? "complete" : "audio",
          status == Status.failed ? "PROCESS_INTERRUPTED" : null, status == Status.failed,
          status == Status.succeeded ? m4a.toString() : null);
      db.update("insert into script_segments values (?,0,?::jsonb)", job, script);
      // Insert out of order to exercise the explicit chunk_order read after migration.
      for (int order : List.of(1, 0)) {
        var payload = json.readTree(audio);
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("group")).put("order", order);
        db.update("insert into audio_groups values (?,?,?::jsonb)", job, order, payload.toString());
      }
      UUID attemptId = UUID.randomUUID();
      var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(attempt);
      payload.put("id", attemptId.toString());
      db.update("insert into generation_attempts values (?,?,?::jsonb)", attemptId, job, payload.toString());
    }

    var before = snapshots(db, false);
    var relation = db.queryForMap("select oid,relfilenode from pg_class where oid='audio_groups'::regclass");
    long tableOid = ((Number) relation.get("oid")).longValue();
    var constraints = constraints(db, tableOid);
    var triggers = triggers(db, tableOid);
    var indexes = indexes(db, tableOid);

    assertEquals(1, flyway("4").migrate().migrationsExecuted);
    assertEquals(before, snapshots(db, true), "all row counts and canonical JSONB row checksums survive");
    assertNull(db.queryForObject("select to_regclass('audio_groups')", String.class));
    assertEquals(relation, db.queryForMap(
        "select oid,relfilenode from pg_class where oid='validated_audio_chunks'::regclass"));
    assertEquals(constraints, constraints(db, tableOid));
    assertEquals(triggers, triggers(db, tableOid));
    assertEquals(indexes, indexes(db, tableOid));
    assertEquals(Set.of("validated_audio_chunks_pkey", "validated_audio_chunks_generation_id_fkey"),
        new HashSet<>(db.queryForList("select conname from pg_constraint where conrelid=?::oid", String.class, tableOid)));
    assertEquals(List.of("validated_audio_chunks_immutable"), db.queryForList(
        "select tgname from pg_trigger where tgrelid=?::oid and not tgisinternal", String.class, tableOid));
    assertEquals(List.of("validated_audio_chunks_pkey"), db.queryForList(
        "select indexname from pg_indexes where tablename='validated_audio_chunks'", String.class));

    UUID job = jobs.get(Status.failed);
    var saved = db.queryForList(
        "select payload::text from validated_audio_chunks where generation_id=? order by chunk_order",
        String.class, job);
    assertEquals(List.of(0, 1), saved.stream().map(s -> {
      try { return json.readValue(s, ValidatedAudioChunk.class).group().order(); }
      catch (Exception e) { throw new AssertionError(e); }
    }).toList());
    assertEquals(receipt, json.readValue(saved.getFirst(), ValidatedAudioChunk.class));
    artifacts.verify(json.readValue(saved.getFirst(), ValidatedAudioChunk.class));
    assertEquals(json.readTree(audio), json.readTree(saved.getFirst()));
    assertEquals(json.readValue(script, DialogueSegment.class), json.readValue(db.queryForObject(
        "select payload::text from script_segments where generation_id=?", String.class, job), DialogueSegment.class));
    var savedAttempt = json.readValue(db.queryForObject(
        "select payload::text from generation_attempts where generation_id=?", String.class, job), ProviderAttempt.class);
    assertEquals("group-0", savedAttempt.unitKey());
    assertEquals(123L, savedAttempt.usage().get("inputTextTokens"));
    assertEquals(0.000123, savedAttempt.estimatedCost());

    rejects(db, "update validated_audio_chunks set payload='{}' where generation_id=?", job);
    rejects(db, "delete from validated_audio_chunks where generation_id=?", job);
    rejects(db, "insert into validated_audio_chunks select * from validated_audio_chunks where generation_id=?", job);
    rejects(db, "insert into validated_audio_chunks values (?,0,'{}')", UUID.randomUUID());
    rejects(db, "update script_segments set payload='{}' where generation_id=?", job);
    rejects(db, "delete from script_segments where generation_id=?", job);
    rejects(db, "update source_revisions set content_hash='changed' where id=?", revision);
    rejects(db, "delete from source_blocks where revision_id=?", revision);
    rejects(db, "insert into generations select * from generations where id=?", job);
    rejects(db, "update generations set status='queued' where id=?", job);
    assertEquals(before, snapshots(db, true));
    assertArrayEquals(pcmBytes, Files.readAllBytes(pcm));
    assertArrayEquals(m4aBytes, Files.readAllBytes(m4a));
    assertEquals(pcmModified, Files.getLastModifiedTime(pcm));
    assertEquals(m4aModified, Files.getLastModifiedTime(m4a));
    assertEquals(0, flyway("4").migrate().migrationsExecuted);
  }

  private static Map<String, Map<String, Object>> snapshots(JdbcTemplate db, boolean migrated) {
    var snapshots = new LinkedHashMap<String, Map<String, Object>>();
    for (String table : List.of("books", "chapters", "source_revisions", "source_blocks",
        "generations", "script_segments", "generation_attempts", "audio_groups")) {
      String select = table.equals("audio_groups") && migrated
          ? "select generation_id,chunk_order as group_order,payload from validated_audio_chunks"
          : "select * from " + table;
      snapshots.put(table, db.queryForMap("""
          select count(*) as row_count,
              md5(coalesce(jsonb_agg(to_jsonb(snapshot_row) order by to_jsonb(snapshot_row)::text)::text,'[]')) as checksum
          from (
          """ + select + ") snapshot_row"));
    }
    return snapshots;
  }

  private static List<Map<String, Object>> constraints(JdbcTemplate db, long table) {
    return db.queryForList("""
        select oid,contype,conkey::text,confrelid,confkey::text,conindid,convalidated
        from pg_constraint where conrelid=?::oid order by oid
        """, table);
  }

  private static List<Map<String, Object>> triggers(JdbcTemplate db, long table) {
    return db.queryForList("""
        select oid,tgfoid,tgtype,tgenabled,tgisinternal,tgconstraint
        from pg_trigger where tgrelid=?::oid order by oid
        """, table);
  }

  private static List<Map<String, Object>> indexes(JdbcTemplate db, long table) {
    return db.queryForList("""
        select indexrelid,indisunique,indisprimary,indisvalid,indkey::text
        from pg_index where indrelid=?::oid order by indexrelid
        """, table);
  }

  private static void rejects(JdbcTemplate db, String sql, UUID id) {
    assertThrows(DataAccessException.class, () -> db.update(sql, id));
  }
}
