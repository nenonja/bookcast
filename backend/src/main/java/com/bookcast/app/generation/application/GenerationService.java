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

  public GenerationService(
      Library library,
      GenerationStore store,
      TextGeneration text,
      SpeechRendering speech,
      AudioArtifacts artifacts,
      @Value("${bookcast.source-unit-words:800}") int unitWords) {
    this.library = library;
    this.store = store;
    this.text = text;
    this.speech = speech;
    this.artifacts = artifacts;
    this.unitWords = unitWords;
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
    store.totals(job.id(), units.size(), 0);
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
      if (error == null) {
        try {
          if (result.segment() == null) throw new IllegalArgumentException();
          result.segment().validate(i, units.get(i).stream().map(ChapterSource.Block::id).toList());
        } catch (Exception e) {
          error = "INVALID_DIALOGUE_OUTPUT";
        }
      }
      finish(job.id(), attempt, "dialogue", "segment-" + i, profile, began, metadata, error);
      if (error != null) {
        store.state(job.id(), "failed", "dialogue", error, metadata.retryable(), null);
        return;
      }
      store.segment(job.id(), result.segment());
      segments.add(result.segment());
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
      store.state(job.id(), "running", "audio", null, false, null);
      var group = groups.get(i);
      UUID attempt = UUID.randomUUID();
      long began = System.nanoTime();
      var profile = speech.profile();
      store.attempt(
          job.id(),
          new Attempt(
              attempt,
              "audio",
              "group-" + i,
              profile.provider(),
              profile.model(),
              "attempting",
              0,
              Map.of(),
              null,
              false,
              null));
      AudioResult result;
      try {
        result = speech.render(group, job.id(), attempt);
      } catch (Exception e) {
        result =
            new AudioResult(new byte[0], "", Metadata.error("PROVIDER_TRANSPORT_FAILURE", true));
      }
      var metadata = result.metadata();
      String error = metadata.errorCode();
      String path = artifacts.preserve(job.id(), attempt, result.pcm(), result.transcript());
      AudioReceipt receipt = null;
      if (error == null) {
        try {
          receipt = artifacts.validate(group, path, result.transcript());
        } catch (Exception e) {
          error = "INVALID_AUDIO_WAVEFORM";
        }
      }
      finish(job.id(), attempt, "audio", "group-" + i, profile, began, metadata, error);
      if (error != null) {
        store.state(
            job.id(),
            "failed",
            "audio",
            error,
            metadata.retryable() || error.equals("INVALID_AUDIO_WAVEFORM"),
            null);
        return;
      }
      store.audio(job.id(), receipt);
      receipts.add(receipt);
    }
    store.state(job.id(), "running", "assembly", null, false, null);
    String finalPath = artifacts.assemble(job.id(), receipts);
    store.state(job.id(), "succeeded", "complete", null, false, finalPath);
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
