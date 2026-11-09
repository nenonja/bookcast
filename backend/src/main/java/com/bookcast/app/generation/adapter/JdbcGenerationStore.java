package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.GenerationStore;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores generation state, accepted checkpoints and attempt snapshots in PostgreSQL.
 *
 * <p>Job columns describe current progress; segments, audio receipts and attempts use JSON payloads.
 * Accepted checkpoints are insert-only here and protected against updates/deletion by migration
 * triggers. An attempt instead replaces its payload as it moves from pending to a final outcome;
 * callers assign a fresh ID for each separate provider attempt, preserving paid-attempt history.
 *
 * <p>The application owns validation, transition decisions, provider calls and retry policy. This
 * adapter persists their results without validating dialogue, PCM or artifact files. Collection
 * reads return the matching child rows, including an empty list for an unknown job; {@link #get(UUID)}
 * throws {@link NoSuchElementException} for an unknown job. Database failures propagate, while JSON
 * encoding/decoding failures become payload-free {@code PERSISTENCE_ENCODING} or
 * {@code PERSISTENCE_DECODING} errors.
 *
 * <p>Ordinary job lookups use a restricted Spring Data JDBC repository. SQL commands and repository
 * reads share the same JDBC transaction manager; RETURNING rows use Spring's record row mapper and
 * the same explicit persistence-to-application mapping. There is no ORM-managed entity graph.
 */
@Repository
public class JdbcGenerationStore implements GenerationStore {
  private static final DataClassRowMapper<GenerationRow> JOB_ROWS =
      new DataClassRowMapper<>(GenerationRow.class);
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final GenerationReadRepository reads;

  public JdbcGenerationStore(JdbcTemplate db, ObjectMapper json, GenerationReadRepository reads) {
    this.db = db;
    this.json = json;
    this.reads = reads;
  }

  private Job row(java.sql.ResultSet r) throws java.sql.SQLException {
    return JOB_ROWS.mapRow(r, 0).toJob();
  }

  /**
   * Inserts a queued job or returns the job selected by idempotency/active-work constraints.
   *
   * <p>An existing request ID must refer to the same chapter and revision; otherwise this throws
   * {@code IDEMPOTENCY_CONFLICT}. If another request already has queued/running work for the revision,
   * that active job is returned instead. The unique request constraint and partial active-revision
   * index arbitrate competing inserts. Previously persisted model names are returned unchanged;
   * model compatibility is checked by the application, not by this method.
   *
   * @param request non-null client idempotency key
   * @param chapter non-null chapter ID, already checked against the revision by the caller
   * @param revision non-null immutable source revision ID
   * @param text dialogue model name stored only for a newly inserted job
   * @param audio audio model name stored only for a newly inserted job
   * @return the newly queued job, a matching prior request, or active work for the revision
   * @throws IllegalStateException for an idempotency conflict, or {@code GENERATION_CONFLICT} if a
   *     rejected insert cannot be resolved to a prior request or currently active job
   */
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
    var existing = reads.findByRequestId(request);
    if (existing.isPresent()) {
      var j = existing.get().toJob();
      if (!j.chapterId().equals(chapter) || !j.sourceRevisionId().equals(revision))
        throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
      return j;
    }
    var rows = reads.findByRevisionIdAndStatusIn(revision, List.of(Status.queued, Status.running));
    if (rows.isEmpty()) throw new IllegalStateException("GENERATION_CONFLICT");
    return rows.getFirst().toJob();
  }

  public Job get(UUID id) {
    return reads.findById(id).map(GenerationRow::toJob).orElseThrow(NoSuchElementException::new);
  }

  public List<Job> list(UUID chapter) {
    var jobs = new ArrayList<Job>();
    reads.findByChapterIdOrderByCreatedAtDescIdDesc(chapter).forEach(row -> jobs.add(row.toJob()));
    return jobs;
  }

  /**
   * Atomically takes the oldest queued job whose row is not locked by another transaction, setting
   * its status to running and initial stage to dialogue. The claim uses a single statement; this
   * adapter does not start a transaction spanning provider work. This row-locking operation is not
   * a lease or a replacement for the application's single-backend recovery policy.
   *
   * @return the updated job, or empty when no unlocked queued job is available
   */
  public Optional<Job> claim() {
    return db
        .query(
            "update generations set status='running',stage='dialogue' where id=(select id from generations where status='queued' order by created_at limit 1 for update skip locked) returning *",
            (r, n) -> row(r))
        .stream()
        .findFirst();
  }

  /**
   * Marks all running jobs failed and all still-attempting payloads interrupted for startup recovery.
   * Jobs become manually retryable; attempt payloads receive an end time, an interrupted retry
   * classification and unknown-usage flag. Existing usage/cost fields and accepted checkpoints are
   * retained, and no job is queued. The two updates are separate statements, without a transaction
   * declared on this method; recovery must run before this backend starts polling.
   */
  public void interrupted() {
    db.update(
        "update generations set status='failed',stage='interrupted',error_code='PROCESS_INTERRUPTED',retryable=true where status='running'");
    db.update(
        "update generation_attempts set payload=(jsonb_set(jsonb_set(jsonb_set(payload,'{outcome}',to_jsonb('interrupted'::text)),'{errorCode}',to_jsonb('PROCESS_INTERRUPTED'::text)),'{retryable}','true'::jsonb) || jsonb_build_object('endedAt',?::text,'retryClassification','INTERRUPTED','usageUnknown',true)) where payload->>'outcome'='attempting'",
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
  }

  /**
   * Replaces current status, stage, error, retryability and final audio reference without changing
   * checkpoints or attempt history. Null error/path values clear the corresponding stored columns.
   * The caller owns transition validation; an unknown ID updates no row.
   *
   * @param id non-null job ID
   * @param status persisted job status, subject to the database status constraint
   * @param stage application progress label
   * @param error safe error code, or {@code null}
   * @param retryable recorded failure assessment, not an instruction to queue work
   * @param path artifact reference, or {@code null} when no final audio is being recorded
   */
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

  /**
   * Queues a failed job and clears its error code, retaining totals, retryability metadata, model
   * names, audio reference and all checkpoints/attempts. Only failed status is checked here; the
   * recorded retryable flag is not a prerequisite for an explicit manual retry.
   *
   * @param id non-null job ID whose status must currently be failed
   * @throws IllegalStateException with {@code GENERATION_NOT_FAILED} for an unknown or nonfailed job
   */
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

  /**
   * Inserts a new attempt or replaces the entire JSON payload for an existing attempt ID. Repeated
   * writes represent progress/finalization of one call, not additional paid attempts. On conflict,
   * the original generation association is retained, so callers must reuse an ID only within its
   * original job. No provider usage or cost is inferred while encoding the supplied snapshot.
   *
   * @param job non-null owning generation ID for a new attempt
   * @param attempt non-null snapshot whose ID identifies one call throughout its lifecycle
   */
  public void attempt(UUID job, Attempt attempt) {
    db.update(
        "insert into generation_attempts values (?,?,?::jsonb) on conflict (id) do update set payload=excluded.payload",
        attempt.id(),
        job,
        encode(attempt));
  }

  /**
   * Reads all recorded attempt snapshots without imposing an order. Callers must not infer attempt
   * chronology or retry sequence from the returned list's position.
   *
   * @param id non-null generation ID
   * @return decoded snapshots, or an empty list when no rows match
   */
  public List<Attempt> attempts(UUID id) {
    return db.query(
        "select payload::text from generation_attempts where generation_id=?",
        (r, n) -> decode(r.getString(1), Attempt.class),
        id);
  }
}
