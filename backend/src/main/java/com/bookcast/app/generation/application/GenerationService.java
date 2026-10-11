package com.bookcast.app.generation.application;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.*;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GenerationService {
  private final Library library;
  private final GenerationStore store;
  private final TextGeneration text;
  private final SpeechRendering speech;
  private final AudioArtifacts artifacts;
  private final int unitWords;
  private final AudioRetryPolicy audioRetry;

  public GenerationService(
      Library library,
      GenerationStore store,
      TextGeneration text,
      SpeechRendering speech,
      AudioArtifacts artifacts,
      @Value("${bookcast.source-unit-words:800}") int unitWords,
      AudioRetryPolicy audioRetry) {
    this.library = library;
    this.store = store;
    this.text = text;
    this.speech = speech;
    this.artifacts = artifacts;
    this.unitWords = unitWords;
    this.audioRetry = audioRetry;
  }

  public Job start(UUID chapter, UUID revision, UUID request) {
    library.revision(chapter, revision);
    return store.create(
        request, chapter, revision, text.profile().model(), speech.profile().model());
  }

  public Job get(UUID id) {
    return store.get(id);
  }

  public List<Job> list(UUID chapter) {
    library.chapter(chapter);
    return store.list(chapter);
  }

  public List<Segment> segments(UUID id) {
    return store.segments(id);
  }

  public List<AudioReceipt> audio(UUID id) {
    return store.audio(id);
  }

  public List<Attempt> attempts(UUID id) {
    return store.attempts(id);
  }

  public Job retry(UUID id) {
    Podcast.requireRetryable(store.get(id));
    store.retry(id);
    return store.get(id);
  }

  public String audioPath(UUID id) {
    var j = store.get(id);
    if (j.status() != Status.succeeded || j.audioPath() == null)
      throw new IllegalStateException("AUDIO_NOT_READY");
    return artifacts.absolutePath(j.audioPath());
  }

  public record DownloadAudio(String path, String bookTitle, String chapterTitle) {}

  public DownloadAudio downloadAudio(UUID id) {
    var job = store.get(id);
    String path;
    try {
      path = audioPath(id);
    } catch (NoSuchElementException e) {
      throw new AudioArtifactUnavailable();
    }
    var source = library.revision(job.chapterId(), job.sourceRevisionId());
    return new DownloadAudio(path, source.bookTitle(), source.title());
  }

  public static class AudioArtifactUnavailable extends RuntimeException {}

  public void interrupted() {
    store.interrupted();
  }

  public boolean workOne() {
    var claimed = store.claim();
    if (claimed.isEmpty()) return false;
    var job = claimed.get();
    try {
      run(job);
    } catch (Exception e) {
      String code = e.getMessage();
      if (code == null || !code.matches("[A-Z_]{3,80}")) code = "LOCAL_EXECUTION_FAILURE";
      store.state(job.id(), "failed", "failed", code, false, null);
    }
    return true;
  }

  private List<List<ChapterSource.Block>> units(ChapterSource source) {
    var result = new ArrayList<List<ChapterSource.Block>>();
    var current = new ArrayList<ChapterSource.Block>();
    int count = 0;
    for (var b : source.blocks()) {
      if (b.excludedReason() != null) continue;
      int words = b.text().split("\\s+").length;
      if (!current.isEmpty() && count + words > unitWords) {
        result.add(List.copyOf(current));
        current.clear();
        count = 0;
      }
      current.add(b);
      count += words;
    }
    if (!current.isEmpty()) result.add(List.copyOf(current));
    return result;
  }

  private void run(Job job) {
    if (!job.generationModel().equals(text.profile().model())
        || !job.audioModel().equals(speech.profile().model()))
      throw new IllegalStateException("GENERATION_PROFILE_CHANGED");
    var source = library.revision(job.chapterId(), job.sourceRevisionId());
    var units = units(source);
    store.totals(job.id(), units.size(), job.totalAudio());
    var segments = new ArrayList<>(store.segments(job.id()));
    if (segments.size() > units.size()) throw new IllegalStateException("GENERATION_PLAN_CHANGED");
    for (int i = 0; i < segments.size(); i++)
      segments.get(i).validate(i, units.get(i).stream().map(ChapterSource.Block::id).toList());

    for (int i = segments.size(); i < units.size(); i++) {
      store.state(job.id(), "running", "dialogue", null, false, null);
      var previous = segments.isEmpty() ? List.<Turn>of() : segments.getLast().dialogueTurns();
      previous = previous.subList(Math.max(0, previous.size() - 4), previous.size());
      UUID attempt = UUID.randomUUID();
      long began = System.nanoTime();
      var profile = text.profile();
      store.attempt(
          job.id(),
          new Attempt(
              attempt,
              "dialogue",
              "segment-" + i,
              profile.provider(),
              profile.model(),
              "attempting",
              0,
              Map.of(),
              null,
              false,
              null));
      TextResult result;
      try {
        result =
            text.generate(
                new TextRequest(
                    job.id(),
                    attempt,
                    i,
                    units.get(i),
                    List.copyOf(previous),
                    i == 0,
                    i == units.size() - 1,
                    source.sourceLanguage(),
                    "fi"));
      } catch (Exception e) {
        result = new TextResult(null, Metadata.error("PROVIDER_TRANSPORT_FAILURE", true));
      }
      var metadata = result.metadata();
      String error = metadata.errorCode();
      Segment acceptedSegment = null;
      if (error == null) {
        try {
          if (result.segment() == null) throw new IllegalArgumentException();
          acceptedSegment = DialogueText.normalize(result.segment());
          acceptedSegment.validate(i, units.get(i).stream().map(ChapterSource.Block::id).toList());
        } catch (DialogueText.SpeakerMismatch e) {
          error = "DIALOGUE_SPEAKER_PREFIX_MISMATCH";
        } catch (Exception e) {
          error = "INVALID_DIALOGUE_OUTPUT";
        }
      }
      finish(job.id(), attempt, "dialogue", "segment-" + i, profile, began, metadata, error);
      if (error != null) {
        store.state(job.id(), "failed", "dialogue", error, metadata.retryable(), null);
        return;
      }
      store.segment(job.id(), acceptedSegment);
      segments.add(acceptedSegment);
    }
    var groups = AudioGroups.plan(segments);
    store.totals(job.id(), units.size(), groups.size());
    var receipts = new ArrayList<>(store.audio(job.id()));
    for (var receipt : receipts) {
      if (!receipt.group().equals(groups.get(receipt.group().order())))
        throw new IllegalStateException("AUDIO_PLAN_CHANGED");
      artifacts.verify(receipt);
    }
    for (int i = receipts.size(); i < groups.size(); i++) {
      var receipt = renderAudio(job, groups.get(i));
      if (receipt == null) return;
      store.audio(job.id(), receipt);
      receipts.add(receipt);
    }
    store.state(job.id(), "running", "assembly", null, false, null);
    String finalPath = artifacts.assemble(job.id(), receipts);
    store.state(job.id(), "succeeded", "complete", null, false, finalPath);
  }

  private AudioReceipt renderAudio(Job job, Group group) {
    var profile = speech.profile();
    for (int number = 1; number <= AudioRetryPolicy.MAX_ATTEMPTS; number++) {
      if (Thread.currentThread().isInterrupted())
        throw new IllegalStateException("PROCESS_INTERRUPTED");
      store.state(
          job.id(),
          "running",
          number == 1 ? "audio" : "audio_retry:" + (group.order() + 1) + ":" + number,
          null,
          false,
          null);
      UUID attempt = UUID.randomUUID();
      long began = System.nanoTime();
      var started = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
      store.attempt(
          job.id(),
          new Attempt(
              attempt,
              "audio",
              "group-" + group.order(),
              profile.provider(),
              profile.model(),
              "attempting",
              0,
              Map.of(),
              null,
              false,
              null,
              number,
              started,
              null,
              true,
              "PENDING",
              null,
              null,
              null));
      AudioResult result;
      try {
        result = speech.render(group, job.id(), attempt);
      } catch (Exception e) {
        // An unexpected adapter/local exception is not evidence of a transient provider failure.
        result = new AudioResult(new byte[0], "", Metadata.error("AUDIO_ADAPTER_FAILURE", false));
      }
      var metadata = result.metadata();
      String error = metadata.errorCode();
      String path = null;
      try {
        path = artifacts.preserve(job.id(), attempt, result.pcm(), result.transcript());
      } catch (RuntimeException e) {
        error = "AUDIO_ARTIFACT_WRITE_FAILURE";
      }
      AudioReceipt receipt = null;
      if (error == null) {
        try {
          receipt = artifacts.validate(group, path, result.transcript());
        } catch (Exception e) {
          error = "INVALID_AUDIO_WAVEFORM";
        }
      }
      boolean transientFailure =
          error != null
              && error.equals(metadata.errorCode())
              && AudioRetryPolicy.transientFailure(metadata);
      Long delay =
          transientFailure
                  && number < AudioRetryPolicy.MAX_ATTEMPTS
                  && !Thread.currentThread().isInterrupted()
              ? audioRetry.delay(number, metadata)
              : null;
      store.attempt(
          job.id(),
          new Attempt(
              attempt,
              "audio",
              "group-" + group.order(),
              profile.provider(),
              profile.model(),
              error == null ? "succeeded" : "failed",
              (System.nanoTime() - began) / 1000000,
              metadata.usage(),
              metadata.cost(),
              transientFailure,
              error,
              number,
              started,
              java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
              metadata.usage().isEmpty(),
              error == null ? "SUCCESS" : transientFailure ? "TRANSIENT" : "NON_TRANSIENT",
              metadata.providerStatus(),
              metadata.providerCode(),
              delay));
      if (error == null) return receipt;
      if (delay == null) {
        store.state(job.id(), "failed", "audio", error, transientFailure, null);
        return null;
      }
      store.state(
          job.id(),
          "running",
          "audio_retry:" + (group.order() + 1) + ":" + (number + 1),
          null,
          false,
          null);
      try {
        // No transaction spans the provider call or this wait.
        audioRetry.pause(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("PROCESS_INTERRUPTED");
      }
    }
    throw new IllegalStateException("AUDIO_ATTEMPT_LIMIT");
  }

  private void finish(
      UUID job,
      UUID id,
      String capability,
      String unit,
      Profile profile,
      long begin,
      Metadata metadata,
      String error) {
    store.attempt(
        job,
        new Attempt(
            id,
            capability,
            unit,
            profile.provider(),
            profile.model(),
            error == null ? "succeeded" : "failed",
            (System.nanoTime() - begin) / 1000000,
            metadata.usage(),
            metadata.cost(),
            metadata.retryable(),
            error));
  }
}
