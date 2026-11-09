package com.bookcast.app.generation.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.application.Capabilities.AudioMetadata;
import com.bookcast.app.generation.domain.AudioGroups;
import com.bookcast.app.generation.domain.Podcast.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LocalAudioMetadataTest {
  @TempDir Path data;

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {7, 65_536})
  void newM4aContainsReadableTagsAndHashWithoutChangingAcceptedPcm(Integer chapterNumber)
      throws Exception {
    var json = new ObjectMapper();
    var artifacts = new LocalAudioArtifacts(data.toString(), 30);
    UUID job = UUID.randomUUID(), attempt = UUID.randomUUID();
    String script = "Tämä on suomalainen selitys.";
    var group = new Group(0, 0, Speaker.EXPERT, script, AudioGroups.hash(script), List.of());
    var audio = new FakeCapabilities().fakeSpeech().render(group, job, attempt);
    String raw = artifacts.preserve(job, attempt, audio.pcm(), audio.transcript());
    var receipt = artifacts.validate(group, raw, audio.transcript());
    var metadata = new AudioMetadata("Älykäs kirja: lähdekoodi / osa yksi",
        "Luku: \"Ääni\" / käyttöoikeudet", chapterNumber);

    Path media = data.resolve(artifacts.assemble(job, List.of(receipt), metadata));
    Process probe = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries",
        "format_tags=title,artist,album,album_artist,track:stream=codec_name", "-of", "json",
        media.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try {
      assertTrue(probe.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, probe.exitValue());
      var result = json.readTree(probe.getInputStream());
      var tags = result.path("format").path("tags");
      assertEquals(metadata.chapterTitle(), tags.path("title").asText());
      assertEquals(metadata.bookTitle(), tags.path("album").asText());
      assertEquals("Bookcast", tags.path("artist").asText());
      assertEquals("Bookcast", tags.path("album_artist").asText());
      if (chapterNumber != null && chapterNumber <= 65_535)
        assertEquals(chapterNumber.toString(), tags.path("track").asText());
      else assertTrue(tags.path("track").isMissingNode(), "absent/overflowing track must not become zero");
      assertEquals("aac", result.path("streams").get(0).path("codec_name").asText());
    } finally {
      if (probe.isAlive()) probe.destroyForcibly();
    }
    assertEquals(AudioGroups.hash(Files.readAllBytes(media)),
        Files.readString(Path.of(media + ".sha256")));
    assertArrayEquals(audio.pcm(), Files.readAllBytes(data.resolve(raw)));
    artifacts.verify(receipt);
  }
}
