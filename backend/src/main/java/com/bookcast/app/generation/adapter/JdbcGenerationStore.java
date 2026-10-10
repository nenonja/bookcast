package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.GenerationStore;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGenerationStore implements GenerationStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public JdbcGenerationStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  private Job row(java.sql.ResultSet r) throws java.sql.SQLException {
    return new Job(
        r.getObject("id", UUID.class),
        r.getObject("chapter_id", UUID.class),
        r.getObject("revision_id", UUID.class),
        Status.valueOf(r.getString("status")),
        r.getString("stage"),
        r.getInt("total_segments"),
        r.getInt("total_audio"),
        r.getString("generation_model"),
        r.getString("audio_model"),
        r.getString("error_code"),
        r.getBoolean("retryable"),
        r.getString("audio_path"));
  }

  @Transactional
  public Job create(UUID request, UUID chapter, UUID revision, String text, String audio) {
    UUID id = UUID.randomUUID();
    db.update(
        "insert into generations(id,request_id,chapter_id,revision_id,status,stage,generation_model,audio_model) values (?,?,?,?,'queued','queued',?,?) on conflict do nothing",
        id,
        request,
        chapter,
        revision,
        text,
        audio);
    var rows = db.query("select * from generations where request_id=?", (r, n) -> row(r), request);
    if (!rows.isEmpty()) {
      var j = rows.getFirst();
      if (!j.chapterId().equals(chapter) || !j.sourceRevisionId().equals(revision))
        throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
      return j;
    }
    rows =
        db.query(
            "select * from generations where revision_id=? and status in ('queued','running')",
            (r, n) -> row(r),
            revision);
    if (rows.isEmpty()) throw new IllegalStateException("GENERATION_CONFLICT");
    return rows.getFirst();
  }

  public Job get(UUID id) {
    var rows = db.query("select * from generations where id=?", (r, n) -> row(r), id);
    if (rows.isEmpty()) throw new NoSuchElementException();
    return rows.getFirst();
  }

  public List<Job> list(UUID chapter) {
    return db.query(
        "select * from generations where chapter_id=? order by created_at desc",
        (r, n) -> row(r),
        chapter);
  }

  public Optional<Job> claim() {
    return db
        .query(
            "update generations set status='running',stage='dialogue' where id=(select id from generations where status='queued' order by created_at limit 1 for update skip locked) returning *",
            (r, n) -> row(r))
        .stream()
        .findFirst();
  }

  public void interrupted() {
    db.update(
        "update generations set status='failed',stage='interrupted',error_code='PROCESS_INTERRUPTED',retryable=true where status='running'");
    db.update(
        "update generation_attempts set payload=jsonb_set(jsonb_set(jsonb_set(payload,'{outcome}',to_jsonb('interrupted'::text)),'{errorCode}',to_jsonb('PROCESS_INTERRUPTED'::text)),'{retryable}','true'::jsonb) where payload->>'outcome'='attempting'");
  }

  public void state(
      UUID id, String status, String stage, String error, boolean retryable, String path) {
    db.update(
        "update generations set status=?,stage=?,error_code=?,retryable=?,audio_path=? where id=?",
        status,
        stage,
        error,
        retryable,
        path,
        id);
  }

  public void totals(UUID id, int segments, int audio) {
    db.update(
        "update generations set total_segments=?,total_audio=? where id=?", segments, audio, id);
  }

  public void retry(UUID id) {
    if (db.update(
            "update generations set status='queued',stage='queued',error_code=null where id=? and status='failed'",
            id)
        != 1) throw new IllegalStateException("GENERATION_NOT_FAILED");
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("PERSISTENCE_ENCODING");
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("PERSISTENCE_DECODING");
    }
  }

  public List<Segment> segments(UUID id) {
    return db.query(
        "select payload::text from script_segments where generation_id=? order by segment_order",
        (r, n) -> decode(r.getString(1), Segment.class),
        id);
  }

  public void segment(UUID id, Segment segment) {
    db.update(
        "insert into script_segments values (?,?,?::jsonb)", id, segment.order(), encode(segment));
  }

  public List<AudioReceipt> audio(UUID id) {
    return db.query(
        "select payload::text from audio_groups where generation_id=? order by group_order",
        (r, n) -> decode(r.getString(1), AudioReceipt.class),
        id);
  }

  public void audio(UUID id, AudioReceipt receipt) {
    db.update(
        "insert into audio_groups values (?,?,?::jsonb)",
        id,
        receipt.group().order(),
        encode(receipt));
  }

  public void attempt(UUID job, Attempt attempt) {
    db.update(
        "insert into generation_attempts values (?,?,?::jsonb) on conflict (id) do update set payload=excluded.payload",
        attempt.id(),
        job,
        encode(attempt));
  }

  public List<Attempt> attempts(UUID id) {
    return db.query(
        "select payload::text from generation_attempts where generation_id=?",
        (r, n) -> decode(r.getString(1), Attempt.class),
        id);
  }
}
