package com.bookcast.app.library.adapter;

import com.bookcast.app.library.*;
import com.bookcast.app.library.application.LibraryStore;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLibraryStore implements LibraryStore {
  private final JdbcTemplate db;

  public JdbcLibraryStore(JdbcTemplate db) {
    this.db = db;
  }

  public UUID book(UUID id, String title) {
    if (id != null) {
      if (db.queryForObject("select count(*) from books where id=?", Integer.class, id) == 0)
        throw new NoSuchElementException();
      return id;
    }
    id = UUID.randomUUID();
    db.update("insert into books values (?,?)", id, title);
    return id;
  }

  public void chapter(UUID id, UUID book, String title) {
    db.update("insert into chapters(id,book_id,title) values (?,?,?)", id, book, title);
  }

  public ChapterSource append(
      UUID id, UUID rev, String language, String hash, List<ChapterSource.Block> blocks) {
    db.update(
        "insert into source_revisions(id,chapter_id,source_language,content_hash) values (?,?,?,?)",
        rev,
        id,
        language,
        hash);
    for (var b : blocks)
      db.update(
          "insert into source_blocks values (?,?,?,?,?)",
          b.id(),
          rev,
          b.order(),
          b.text(),
          b.excludedReason());
    return read(id, rev);
  }

  public ChapterSource read(UUID id, UUID revision) {
    var rows =
        db.query(
            "select c.id,c.book_id,b.title book_title,c.title,r.id revision,r.source_language,r.content_hash,r.created_at from chapters c join books b on b.id=c.book_id join source_revisions r on r.chapter_id=c.id where c.id=? "
                + (revision == null ? "" : "and r.id=? ")
                + "order by r.created_at desc,r.id desc limit 1",
            (rs, n) ->
                new ChapterSource(
                    rs.getObject("id", UUID.class),
                    rs.getObject("book_id", UUID.class),
                    rs.getString("book_title"),
                    rs.getString("title"),
                    rs.getObject("revision", UUID.class),
                    rs.getString("source_language"),
                    rs.getString("content_hash"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    List.of()),
            revision == null ? new Object[] {id} : new Object[] {id, revision});
    if (rows.isEmpty()) throw new NoSuchElementException();
    var c = rows.getFirst();
    var blocks =
        db.query(
            "select * from source_blocks where revision_id=? order by block_order",
            (rs, n) ->
                new ChapterSource.Block(
                    rs.getObject("id", UUID.class),
                    rs.getInt("block_order"),
                    rs.getString("content"),
                    rs.getString("excluded_reason")),
            c.sourceRevisionId());
    return new ChapterSource(
        c.id(),
        c.bookId(),
        c.bookTitle(),
        c.title(),
        c.sourceRevisionId(),
        c.sourceLanguage(),
        c.sourceHash(),
        c.createdAt(),
        blocks);
  }

  public List<ChapterSource> list() {
    return db
        .query(
            "select id from chapters order by created_at desc",
            (rs, n) -> rs.getObject(1, UUID.class))
        .stream()
        .map(id -> read(id, null))
        .toList();
  }
}
