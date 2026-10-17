package com.bookcast.app.library.adapter;

import com.bookcast.app.library.*;
import com.bookcast.app.library.application.LibraryStore;
import com.bookcast.app.library.domain.LibraryProblem;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLibraryStore implements LibraryStore {
  private final JdbcTemplate db;

  public JdbcLibraryStore(JdbcTemplate db) {
    this.db = db;
  }

  public Book createBook(String title) {
    UUID id = UUID.randomUUID();
    db.update("insert into books(id,title) values (?,?)", id, title);
    return new Book(id, title);
  }

  public Book book(UUID id) {
    var rows =
        db.query(
            "select id,title from books where id=?",
            (rs, n) -> new Book(rs.getObject("id", UUID.class), rs.getString("title")),
            id);
    if (rows.isEmpty()) throw new NoSuchElementException();
    return rows.getFirst();
  }

  public List<Book> books() {
    return db.query(
        "select id,title from books order by lower(title),id",
        (rs, n) -> new Book(rs.getObject("id", UUID.class), rs.getString("title")));
  }

  public boolean numberTaken(UUID book, int number, UUID excluding) {
    return db.queryForObject(
            "select count(*) from chapters where book_id=? and chapter_number=? and (?::uuid is null or id<>?::uuid)",
            Integer.class,
            book,
            number,
            excluding,
            excluding)
        > 0;
  }

  public void chapter(UUID id, UUID book, String title, int number) {
    try {
      db.update(
          "insert into chapters(id,book_id,title,chapter_number) values (?,?,?,?)",
          id,
          book,
          title,
          number);
    } catch (DuplicateKeyException e) {
      throw new LibraryProblem("CHAPTER_NUMBER_CONFLICT");
    }
  }

  public void move(UUID chapter, UUID book, int number) {
    try {
      if (db.update(
              "update chapters set book_id=?,chapter_number=? where id=?", book, number, chapter)
          != 1) throw new NoSuchElementException();
    } catch (DuplicateKeyException e) {
      throw new LibraryProblem("CHAPTER_NUMBER_CONFLICT");
    }
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
            "select c.id,c.book_id,c.chapter_number,b.title book_title,c.title,r.id revision,r.source_language,r.content_hash,r.created_at from chapters c join books b on b.id=c.book_id join source_revisions r on r.chapter_id=c.id where c.id=? "
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
                    List.of(),
                    rs.getObject("chapter_number", Integer.class)),
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
        blocks,
        c.chapterNumber());
  }

  public List<ChapterSource> list() {
    return list(null);
  }

  public List<ChapterSource> list(UUID bookId) {
    return db
        .query(
            "select id from chapters "
                + (bookId == null ? "" : "where book_id=? ")
                + "order by "
                + (bookId == null ? "book_id," : "")
                + "chapter_number asc nulls last,created_at,id",
            (rs, n) -> rs.getObject(1, UUID.class),
            bookId == null ? new Object[] {} : new Object[] {bookId})
        .stream()
        .map(id -> read(id, null))
        .toList();
  }
}
