package com.bookcast.app.generation.application;

import com.bookcast.app.generation.domain.Podcast.*;
import java.util.*;

public interface GenerationStore {
  Job create(UUID request, UUID chapter, UUID revision, String generationModel, String audioModel);

  Job get(UUID id);

  List<Job> list(UUID chapter);

  Optional<Job> claim();

  void interrupted();

  void state(
      UUID id, String status, String stage, String error, boolean retryable, String audioPath);

  void totals(UUID id, int segments, int audio);

  void retry(UUID id);

  List<Segment> segments(UUID id);

  void segment(UUID id, Segment segment);

  List<AudioReceipt> audio(UUID id);

  void audio(UUID id, AudioReceipt receipt);

  void attempt(UUID job, Attempt attempt);

  List<Attempt> attempts(UUID id);
}
