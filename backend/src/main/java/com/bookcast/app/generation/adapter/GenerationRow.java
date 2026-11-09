package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.domain.Podcast.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Adapter-owned mapping of one generations row. Checkpoints and attempt JSON are deliberately not
 * aggregate children: this read model does not give Spring Data ownership of their persistence.
 * Flyway owns the table definition. Both repository reads and SQL RETURNING rows use this explicit
 * conversion to the application model; request identity and creation time stay persistence details.
 * Error code and audio reference may be null. PostgreSQL timestamptz retains the creation instant;
 * the PostgreSQL driver supplies its OffsetDateTime value in UTC.
 */
@Table("generations")
record GenerationRow(
    @Id UUID id,
    UUID requestId,
    UUID chapterId,
    UUID revisionId,
    Status status,
    String stage,
    int totalSegments,
    int totalAudio,
    String generationModel,
    String audioModel,
    String errorCode,
    boolean retryable,
    String audioPath,
    OffsetDateTime createdAt) {

  Job toJob() {
    return new Job(id, chapterId, revisionId, status, stage, totalSegments, totalAudio,
        generationModel, audioModel, errorCode, retryable, audioPath);
  }
}
