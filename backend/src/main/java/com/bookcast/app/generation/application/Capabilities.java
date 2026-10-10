package com.bookcast.app.generation.application;

import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.app.library.ChapterSource.Block;
import java.util.*;

public final class Capabilities {
  private Capabilities() {}

  public record Profile(String provider, String model) {}

  public record Metadata(
      Map<String, Long> usage,
      Double cost,
      String errorCode,
      boolean retryable,
      Integer providerStatus,
      String providerCode,
      Long retryAfterMs) {
    public Metadata {
      usage = Map.copyOf(usage);
    }

    public Metadata(Map<String, Long> usage, Double cost, String errorCode, boolean retryable) {
      this(usage, cost, errorCode, retryable, null, null, null);
    }

    public static Metadata error(String code, boolean retryable) {
      return new Metadata(Map.of(), null, code, retryable);
    }
  }

  public record TextRequest(
      UUID generationId,
      UUID attemptId,
      int order,
      List<Block> blocks,
      List<Turn> previousTurns,
      boolean first,
      boolean last,
      String sourceLanguage,
      String outputLanguage) {}

  public record TextResult(Segment segment, Metadata metadata) {}

  public record AudioResult(byte[] pcm, String transcript, Metadata metadata) {}

  public interface TextGeneration {
    Profile profile();

    TextResult generate(TextRequest request);
  }

  public interface SpeechRendering {
    Profile profile();

    AudioResult render(Group group, UUID generationId, UUID attemptId);
  }

  public interface AudioArtifacts {
    String preserve(UUID job, UUID attempt, byte[] pcm, String transcript);

    AudioReceipt validate(Group group, String path, String transcript);

    void verify(AudioReceipt receipt);

    String assemble(UUID job, List<AudioReceipt> receipts);

    String absolutePath(String reference);
  }
}
