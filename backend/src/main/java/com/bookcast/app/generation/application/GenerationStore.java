package com.bookcast.app.generation.application;

import com.bookcast.app.generation.domain.Podcast.*;
import java.util.*;

public interface GenerationStore {
  GenerationJob create(UUID request, UUID chapter, UUID revision, String generationModel, String audioModel);

  GenerationJob get(UUID id);

  List<GenerationJob> list(UUID chapter);

  Optional<GenerationJob> claim();

  void interrupted();

  void state(
      UUID id, String status, String stage, String error, boolean retryable, String audioPath);

  void totals(UUID id, int segments, int audio);

  void retry(UUID id);

  List<DialogueSegment> segments(UUID id);

  void segment(UUID id, DialogueSegment segment);

  List<AudioReceipt> audio(UUID id);

  void audio(UUID id, AudioReceipt receipt);

  void attempt(UUID job, ProviderAttempt attempt);

  List<ProviderAttempt> attempts(UUID id);
}
