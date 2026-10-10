package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.Capabilities.AudioArtifacts;
import com.bookcast.app.generation.domain.AudioGroups;
import com.bookcast.app.generation.domain.Podcast.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalAudioArtifacts implements AudioArtifacts {
  private final Path root;
  private final long encodingTimeoutSeconds;

  public LocalAudioArtifacts(
      @Value("${bookcast.data-dir:./data}") String root,
      @Value("${bookcast.encoding-timeout-seconds:1800}") long encodingTimeoutSeconds) {
    if (encodingTimeoutSeconds <= 0)
      throw new IllegalArgumentException("INVALID_ENCODING_TIMEOUT");
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.encodingTimeoutSeconds = encodingTimeoutSeconds;
  }

  public String preserve(UUID job, UUID attempt, byte[] pcm, String transcript) {
    try {
      Path folder = root.resolve(job.toString()).resolve(attempt.toString());
      Files.createDirectories(folder);
      Files.write(folder.resolve("raw.pcm"), pcm, StandardOpenOption.CREATE_NEW);
      Files.writeString(
          folder.resolve("transcript.txt"), transcript, StandardOpenOption.CREATE_NEW);
      return root.relativize(folder.resolve("raw.pcm")).toString();
    } catch (IOException e) {
      throw new IllegalStateException("ARTIFACT_WRITE_FAILURE");
    }
  }

  public static AudioReceipt waveform(Group group, byte[] bytes, String path, String transcript) {
    if (bytes.length < 4800 || bytes.length % 2 != 0)
      throw new IllegalArgumentException("INVALID_PCM");
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int first = -1, last = -1, run = 0, longest = 0;
    for (int i = 0; i < bytes.length / 2; i++) {
      int v = Math.abs((int) b.getShort());
      if (v > 131) {
        if (first < 0) first = i;
        if (last >= 0) longest = Math.max(longest, run);
        last = i;
        run = 0;
      } else run++;
    }
    double duration = bytes.length / 48000.0,
        trailing = last < 0 ? duration : (bytes.length / 2 - last - 1) / 24000.0,
        envelope = (last - first + 1) / 24000.0;
    double wpm = group.text().strip().split("\\s+").length * 60 / envelope;
    if (first < 0
        || first / 24000.0 >= 2
        || trailing >= 2
        || longest / 24000.0 >= 5
        || wpm > 240
        || transcript.isBlank()) throw new IllegalArgumentException("INVALID_AUDIO_WAVEFORM");
    boolean lexical = !tokens(group.text()).equals(tokens(transcript));
    return new AudioReceipt(
        group,
        path,
        AudioGroups.hash(bytes),
        transcript,
        lexical,
        duration,
        longest / 24000.0,
        trailing,
        wpm);
  }

  private static String tokens(String s) {
    return s.toLowerCase(Locale.forLanguageTag("fi")).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
  }

  public AudioReceipt validate(Group group, String reference, String transcript) {
    try {
      return waveform(group, Files.readAllBytes(path(reference)), reference, transcript);
    } catch (IOException e) {
      throw new IllegalStateException("ARTIFACT_MISSING");
    }
  }

  public void verify(AudioReceipt receipt) {
    try {
      if (!AudioGroups.hash(Files.readAllBytes(path(receipt.path()))).equals(receipt.hash()))
        throw new IllegalStateException("ARTIFACT_HASH_MISMATCH");
    } catch (IOException e) {
      throw new IllegalStateException("ARTIFACT_MISSING");
    }
  }

  private Path path(String ref) {
    Path p = root.resolve(ref).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException("ARTIFACT_REFERENCE");
    return p;
  }

  public String absolutePath(String ref) {
    Path p = path(ref);
    if (!Files.isRegularFile(p)) throw new NoSuchElementException();
    return p.toString();
  }

  public String assemble(UUID job, List<AudioReceipt> receipts) {
    try {
      Path folder = root.resolve(job.toString());
      Files.createDirectories(folder);
      Path pcm = folder.resolve("assembled-" + UUID.randomUUID() + ".pcm"),
          m4a = folder.resolve("chapter-" + UUID.randomUUID() + ".m4a");
      try (var output = Files.newOutputStream(pcm, StandardOpenOption.CREATE_NEW)) {
        for (int i = 0; i < receipts.size(); i++) {
          var r = receipts.get(i);
          if (r.group().order() != i) throw new IllegalStateException("ASSEMBLY_ORDER");
          verify(r);
          Files.copy(path(r.path()), output);
          if (i + 1 < receipts.size())
            output.write(
                new byte
                    [r.group().speaker() == receipts.get(i + 1).group().speaker() ? 9600 : 16800]);
        }
      }
      command(
          List.of(
              "ffmpeg",
              "-nostdin",
              "-v",
              "error",
              "-n",
              "-f",
              "s16le",
              "-ar",
              "24000",
              "-ac",
              "1",
              "-i",
              pcm.toString(),
              "-map_metadata",
              "-1",
              "-c:a",
              "aac",
              "-profile:a",
              "aac_low",
              "-b:a",
              "96k",
              "-movflags",
              "+faststart",
              m4a.toString()));
      command(
          List.of(
              "ffmpeg",
              "-nostdin",
              "-v",
              "error",
              "-xerror",
              "-i",
              m4a.toString(),
              "-f",
              "null",
              "-"));
      Files.writeString(
          folder.resolve(m4a.getFileName() + ".sha256"),
          AudioGroups.hash(Files.readAllBytes(m4a)),
          StandardOpenOption.CREATE_NEW);
      return root.relativize(m4a).toString();
    } catch (IOException e) {
      throw new IllegalStateException("AUDIO_ASSEMBLY_FAILURE");
    }
  }

  private void command(List<String> args) {
    try {
      var process =
          new ProcessBuilder(args)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(encodingTimeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("ENCODING_TIMEOUT");
      }
      if (process.exitValue() != 0) throw new IllegalStateException("AUDIO_ENCODING_FAILURE");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AUDIO_ENCODING_FAILURE");
    } catch (IOException e) {
      throw new IllegalStateException("AUDIO_ENCODING_FAILURE");
    }
  }
}
