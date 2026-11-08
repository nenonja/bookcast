package com.bookcast.app.generation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.domain.AudioGroups;
import com.bookcast.app.generation.domain.Podcast.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalAudioArtifactsProcessTest {
  @TempDir Path data;
  final UUID job = UUID.randomUUID();
  final byte[] pcm = {1, 0, 2, 0};

  AudioReceipt receipt() {
    var group = new Group(0, 0, Speaker.EXPERT, "Selitys.", "script-hash", List.of());
    var artifacts = new LocalAudioArtifacts(data.toString(), 17);
    String reference = artifacts.preserve(job, UUID.randomUUID(), pcm, group.text());
    return new AudioReceipt(group, reference, AudioGroups.hash(pcm), group.text(), false, 1, 0, 0, 60);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void interruptedEncodingOrDecodeCheckTerminatesItsChildAndPreservesInput(int interruptedCommand)
      throws Exception {
    var artifacts = new LocalAudioArtifacts(data.toString(), 17);
    var receipt = receipt();
    Process completed = mock(Process.class), active = mock(Process.class);
    when(completed.waitFor(17, TimeUnit.SECONDS)).thenReturn(true);
    when(active.waitFor(17, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
    when(active.isAlive()).thenReturn(true);
    try (var builders = mockConstruction(ProcessBuilder.class,
        withSettings().defaultAnswer(RETURNS_SELF), (builder, context) ->
            when(builder.start()).thenReturn(context.getCount() == interruptedCommand ? active : completed))) {
      try {
        var error = assertThrows(IllegalStateException.class,
            () -> artifacts.assemble(job, List.of(receipt)));
        assertEquals("AUDIO_ENCODING_FAILURE", error.getMessage());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      verify(active).destroyForcibly();
      verify(completed, never()).destroyForcibly();
      assertEquals(interruptedCommand, builders.constructed().size(), "no later command may start");
    }
    assertArrayEquals(pcm, Files.readAllBytes(data.resolve(receipt.path())));
    try (var files = Files.list(data.resolve(job.toString()))) {
      assertFalse(files.anyMatch(p -> p.toString().endsWith(".sha256")),
          "interrupted assembly must not publish a completed-media checksum");
    }
  }

  @Test
  void timeoutStillTerminatesItsChildWithTheExistingErrorCode() throws Exception {
    var artifacts = new LocalAudioArtifacts(data.toString(), 17);
    var receipt = receipt();
    Process active = mock(Process.class);
    when(active.isAlive()).thenReturn(true);
    try (var builders = mockConstruction(ProcessBuilder.class,
        withSettings().defaultAnswer(RETURNS_SELF), (builder, context) -> when(builder.start()).thenReturn(active))) {
      var error = assertThrows(IllegalStateException.class,
          () -> artifacts.assemble(job, List.of(receipt)));
      assertEquals("ENCODING_TIMEOUT", error.getMessage());
      verify(active).waitFor(17, TimeUnit.SECONDS);
      verify(active).destroyForcibly();
      assertFalse(Thread.currentThread().isInterrupted());
      assertEquals(1, builders.constructed().size());
    }
  }

  @Test
  void completedFailedProcessIsNotTerminatedAgain() throws Exception {
    var artifacts = new LocalAudioArtifacts(data.toString(), 17);
    var receipt = receipt();
    Process completed = mock(Process.class);
    when(completed.waitFor(17, TimeUnit.SECONDS)).thenReturn(true);
    when(completed.exitValue()).thenReturn(1);
    try (var builders = mockConstruction(ProcessBuilder.class,
        withSettings().defaultAnswer(RETURNS_SELF), (builder, context) -> when(builder.start()).thenReturn(completed))) {
      var error = assertThrows(IllegalStateException.class,
          () -> artifacts.assemble(job, List.of(receipt)));
      assertEquals("AUDIO_ENCODING_FAILURE", error.getMessage());
      verify(completed, never()).destroyForcibly();
      assertEquals(1, builders.constructed().size());
    }
  }

  @Test
  void startupFailureKeepsItsErrorCodeWithoutAProcessToCleanUp() {
    var artifacts = new LocalAudioArtifacts(data.toString(), 17);
    var receipt = receipt();
    try (var builders = mockConstruction(ProcessBuilder.class,
        withSettings().defaultAnswer(RETURNS_SELF), (builder, context) ->
            when(builder.start()).thenThrow(new IOException("private startup details")))) {
      var error = assertThrows(IllegalStateException.class,
          () -> artifacts.assemble(job, List.of(receipt)));
      assertEquals("AUDIO_ENCODING_FAILURE", error.getMessage());
      assertEquals(1, builders.constructed().size());
    }
  }
}
