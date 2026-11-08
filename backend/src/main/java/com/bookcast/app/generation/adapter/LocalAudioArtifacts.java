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

/**
 * Preserves attempt audio locally, validates PCM and assembles accepted groups into chapter media.
 *
 * <p>Raw audio is signed 16-bit little-endian mono PCM at 24,000 Hz. Stored references are relative
 * to the normalized data directory; resolution rejects lexical traversal outside that directory.
 * Attempt files use exclusive creation, and assembly uses fresh filenames, leaving prior raw files,
 * checkpoints and completed media in place even when a later operation fails. Writes are not a
 * filesystem transaction, so a failed operation can leave partial diagnostic/output files.
 *
 * <p>The application selects groups and decides whether to accept a checkpoint. This adapter checks
 * waveform plausibility and hashes, not source fidelity or whether every scripted word was spoken.
 * Assembly invokes local ffmpeg twice: AAC-LC encoding followed by a complete decode check. Each
 * subprocess has its own configured timeout in seconds; filesystem work is outside that timeout.
 * No provider calls or retries occur here.
 */
@Component
public class LocalAudioArtifacts implements AudioArtifacts {
  private final Path root;
  private final long encodingTimeoutSeconds;

  public LocalAudioArtifacts(
      @Value("${bookcast.data-dir:./data}") String root,
      @Value("${bookcast.encoding-timeout-seconds:1800}") long encodingTimeoutSeconds) {
    if (encodingTimeoutSeconds <= 0) throw new IllegalArgumentException("INVALID_ENCODING_TIMEOUT");
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.encodingTimeoutSeconds = encodingTimeoutSeconds;
  }

  /**
   * Stores supplied PCM and transcript verbatim as {@code raw.pcm} and {@code transcript.txt},
   * including empty or partial output from a failed attempt. Existing files are not overwritten.
   * If writing the transcript fails after PCM was written, the PCM file remains for investigation.
   * This operation does not validate the waveform or create an accepted checkpoint.
   *
   * @param job non-null generation ID used for the parent directory
   * @param attempt non-null attempt ID whose two target files must not already exist
   * @param pcm non-null bytes in the adapter's raw PCM format
   * @param transcript non-null received transcript, which may be empty or incomplete
   * @return relative reference to the new PCM file
   * @throws IllegalStateException with {@code ARTIFACT_WRITE_FAILURE} on an I/O failure
   */
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

  /**
   * Builds a receipt only when the supplied PCM passes deterministic plausibility gates.
   *
   * <p>Requires an even byte count of at least 4,800 bytes (0.1 seconds). A sample is active when its
   * absolute amplitude exceeds 131. Leading/trailing inactivity must each be shorter than two
   * seconds, and inactivity between active samples shorter than five seconds. Script word count
   * over the first-to-last-active-sample interval must not exceed 240 words per minute. An all-quiet
   * waveform or blank transcript is rejected. Transcript/script lexical differences are recorded
   * after case/punctuation normalization; they do not cause rejection or alter the audio.
   *
   * @param group non-null planned group supplying the script and identity for the receipt
   * @param bytes non-null signed 16-bit little-endian mono PCM at 24,000 Hz
   * @param path artifact reference copied into the receipt without file access or path validation
   * @param transcript non-null provider transcript; must not be blank
   * @return receipt with a SHA-256 hash, durations in seconds and active-envelope words per minute
   * @throws IllegalArgumentException with {@code INVALID_PCM} for size/alignment failure, or
   *     {@code INVALID_AUDIO_WAVEFORM} for a failed plausibility gate
   */
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

  /**
   * Loads a preserved PCM file and applies {@link #waveform(Group, byte[], String, String)}. Path and
   * waveform errors propagate; a file-read I/O failure becomes {@code ARTIFACT_MISSING}.
   *
   * @param group non-null planned group to associate with the receipt
   * @param reference non-null artifact reference resolved within the data directory
   * @param transcript non-null transcript supplied to waveform validation
   * @return the waveform receipt; the caller remains responsible for persisting acceptance
   * @throws IllegalStateException if the PCM cannot be read
   * @throws IllegalArgumentException for a rejected reference or invalid PCM/waveform
   */
  public AudioReceipt validate(Group group, String reference, String transcript) {
    try {
      return waveform(group, Files.readAllBytes(path(reference)), reference, transcript);
    } catch (IOException e) {
      throw new IllegalStateException("ARTIFACT_MISSING");
    }
  }

  /**
   * Checks that the referenced PCM still matches the accepted receipt's hash before reuse. This
   * reads the whole file but does not repeat waveform or transcript validation.
   *
   * @param receipt non-null accepted checkpoint with a data-directory reference and SHA-256 hash
   * @throws IllegalStateException with {@code ARTIFACT_MISSING} on a read failure, or
   *     {@code ARTIFACT_HASH_MISMATCH} when the bytes differ
   * @throws IllegalArgumentException if the reference resolves lexically outside the data directory
   */
  public void verify(AudioReceipt receipt) {
    try {
      if (!AudioGroups.hash(Files.readAllBytes(path(receipt.path()))).equals(receipt.hash()))
        throw new IllegalStateException("ARTIFACT_HASH_MISMATCH");
    } catch (IOException e) {
      throw new IllegalStateException("ARTIFACT_MISSING");
    }
  }

  private Path path(String ref) {
    // This is a lexical containment check; filesystem symlinks are not resolved here.
    Path p = root.resolve(ref).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException("ARTIFACT_REFERENCE");
    return p;
  }

  /**
   * Resolves an existing regular file for playback/download, without decoding it or checking a hash.
   *
   * @param ref non-null artifact reference within the data directory
   * @return normalized absolute filesystem path
   * @throws NoSuchElementException when the resolved path is not a regular file
   * @throws IllegalArgumentException if the reference resolves lexically outside the data directory
   */
  public String absolutePath(String ref) {
    Path p = path(ref);
    if (!Files.isRegularFile(p)) throw new NoSuchElementException();
    return p.toString();
  }

  /**
   * Concatenates accepted PCM in receipt order and produces a fresh, decode-checked M4A plus checksum.
   *
   * <p>Requires group orders starting at zero without gaps and verifies each stored PCM hash before
   * copying. Adjacent groups receive 200 ms of silence for the same speaker or 350 ms for different
   * speakers; source PCM is not trimmed or rewritten. The caller selects the complete receipt list;
   * this method does not query the job's plan or determine whether groups are missing from its end.
   *
   * <p>ffmpeg encodes mono 24 kHz PCM as AAC-LC with a 96 kbit/s target and fast-start metadata, then checks
   * complete decoding before the encoded file's SHA-256 sidecar is written. Scratch PCM and any
   * partial output remain on failure. A successful return alone does not mark the database job
   * succeeded; the application records that transition separately.
   *
   * @param job non-null generation ID for new assembly output files
   * @param receipts non-null ordered list of accepted checkpoints
   * @return data-directory-relative M4A reference after encoding, decode check and checksum succeed
   * @throws IllegalStateException for {@code ASSEMBLY_ORDER}, checkpoint verification errors,
   *     {@code AUDIO_ASSEMBLY_FAILURE} on filesystem I/O, or the subprocess failures described by
   *     {@link #command(List)}
   * @throws IllegalArgumentException for a checkpoint reference outside the data directory
   */
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

  /**
   * Runs one local encoding/verification process, discarding stdout and stderr rather than exposing
   * command diagnostics. A nonzero exit, startup I/O failure or interrupted wait becomes
   * {@code AUDIO_ENCODING_FAILURE}; interruption restores the thread's flag. A timeout requests
   * forcible process termination and raises {@code ENCODING_TIMEOUT}. The interruption branch does not
   * explicitly destroy an already started process.
   */
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
