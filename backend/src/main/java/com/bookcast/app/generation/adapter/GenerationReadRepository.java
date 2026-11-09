package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.domain.Podcast.Status;
import java.util.*;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordinary single-table job lookups only; no inherited save/delete operations are exposed. Reads
 * join an existing transaction, including the store's conflict-safe creation transaction. Atomic
 * commands, startup recovery and JSONB/checkpoint persistence remain in JdbcGenerationStore.
 */
@Transactional(readOnly = true)
interface GenerationReadRepository extends Repository<GenerationRow, UUID> {
  Optional<GenerationRow> findById(UUID id);

  Optional<GenerationRow> findByRequestId(UUID requestId);

  List<GenerationRow> findByRevisionIdAndStatusIn(UUID revisionId, Collection<Status> statuses);

  /** Retains newest-first ordering, resolving previously unspecified timestamp ties by descending ID. */
  List<GenerationRow> findByChapterIdOrderByCreatedAtDescIdDesc(UUID chapterId);
}
