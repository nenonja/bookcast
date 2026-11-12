package com.bookcast.app.generation.adapter.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.domain.Podcast.Speaker;
import com.bookcast.app.generation.domain.Podcast.Group;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenAiSpeechSettingsTest {
  @TempDir Path data;
  final ObjectMapper json = new ObjectMapper();
  static final String MODEL = "gpt-realtime-2.1-mini";

  ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(OpenAiSpeech.class)
        .withPropertyValues("bookcast.data-dir=" + data, "bookcast.api-key=");
  }

  @Test
  void springDefaultsRetainBothVoicesAndSpeed() {
    context().run(c -> {
      assertThat(c).hasNotFailed();
      var settings = c.getBean(OpenAiSpeechSettings.class);
      assertEquals("marin", settings.voice(Speaker.HOST));
      assertEquals("ash", settings.voice(Speaker.EXPERT));
      assertEquals(.95, settings.speed());
    });
  }

  @ParameterizedTest
  @ValueSource(doubles = {.25, 1.2, 1.5})
  void springBindsCustomSettingsIncludingSpeedBoundaries(double speed) {
    context().withPropertyValues("bookcast.speech.host-voice=cedar",
        "bookcast.speech.expert-voice=future_voice", "bookcast.speech.speed=" + speed)
        .run(c -> {
          assertThat(c).hasNotFailed();
          var settings = c.getBean(OpenAiSpeechSettings.class);
          assertEquals("cedar", settings.voice(Speaker.HOST));
          assertEquals("future_voice", settings.voice(Speaker.EXPERT));
          assertEquals(speed, settings.speed());
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"bookcast.speech.host-voice=", "bookcast.speech.host-voice=   ",
      "bookcast.speech.expert-voice=", "bookcast.speech.expert-voice=   ",
      "bookcast.speech.speed=", "bookcast.speech.speed=   ",
      "bookcast.speech.speed=NaN", "bookcast.speech.speed=Infinity",
      "bookcast.speech.speed=-Infinity", "bookcast.speech.speed=0.2499",
      "bookcast.speech.speed=1.5001", "bookcast.speech.speed=fast", "bookcast.audio-model="})
  void invalidConfigurationFailsAtStartup(String property) {
    context().withPropertyValues(property).run(c -> assertThat(c).hasFailed());
  }

  OpenAiSpeech adapter(OpenAiSpeechSettings settings) {
    return new OpenAiSpeech(json, "", data.toString(), MODEL, settings);
  }

  Path snapshot(UUID job) {
    return data.resolve(job.toString()).resolve("speech-settings.json");
  }

  @Test
  void newGenerationPinsCompleteSettingsAndSameSettingsSurviveRestart() throws Exception {
    UUID job = UUID.randomUUID();
    var settings = new OpenAiSpeechSettings("cedar", "coral", 1.2);
    adapter(settings).prepareGeneration(job);
    String saved = Files.readString(snapshot(job));
    var modified = Files.getLastModifiedTime(snapshot(job));
    assertEquals(json.valueToTree(Map.of("model", MODEL, "hostVoice", "cedar",
        "expertVoice", "coral", "speed", 1.2)), json.readTree(saved));
    adapter(settings).prepareGeneration(job);
    assertEquals(saved, Files.readString(snapshot(job)));
    assertEquals(modified, Files.getLastModifiedTime(snapshot(job)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"host", "expert", "speed", "model"})
  void changingEitherVoiceSpeedOrModelRejectsResumeWithoutRewritingSnapshot(String field)
      throws Exception {
    UUID job = UUID.randomUUID();
    adapter(OpenAiSpeechSettings.DEFAULT).prepareGeneration(job);
    String saved = Files.readString(snapshot(job));
    var changed = new OpenAiSpeech(json, "", data.toString(),
        field.equals("model") ? "another-model" : MODEL,
        new OpenAiSpeechSettings(field.equals("host") ? "cedar" : "marin",
            field.equals("expert") ? "coral" : "ash", field.equals("speed") ? 1.2 : .95));
    assertEquals("SPEECH_CONFIGURATION_CHANGED", assertThrows(IllegalStateException.class,
        () -> changed.prepareGeneration(job)).getMessage());
    assertEquals(saved, Files.readString(snapshot(job)));
    UUID attempt = UUID.randomUUID();
    assertEquals("SPEECH_CONFIGURATION_CHANGED", assertThrows(IllegalStateException.class,
        () -> changed.render(new Group(
            0, 0, Speaker.EXPERT, "Selitys.", "hash", List.of()), job, attempt)).getMessage());
    assertFalse(Files.exists(data.resolve(job.toString()).resolve(attempt.toString())));
  }

  @Test
  void freshGenerationPinsCurrentSettingsWithoutChangingPreviousGeneration() throws Exception {
    UUID original = UUID.randomUUID(), fresh = UUID.randomUUID();
    adapter(OpenAiSpeechSettings.DEFAULT).prepareGeneration(original);
    byte[] originalBytes = Files.readAllBytes(snapshot(original));
    var originalModified = Files.getLastModifiedTime(snapshot(original));
    var current = new OpenAiSpeech(json, "", data.toString(), "current-model",
        new OpenAiSpeechSettings("cedar", "coral", 1.2));
    current.prepareGeneration(fresh);
    assertEquals(json.valueToTree(Map.of("model", "current-model", "hostVoice", "cedar",
        "expertVoice", "coral", "speed", 1.2)), json.readTree(Files.readString(snapshot(fresh))));
    assertArrayEquals(originalBytes, Files.readAllBytes(snapshot(original)));
    assertEquals(originalModified, Files.getLastModifiedTime(snapshot(original)));
    assertEquals("SPEECH_CONFIGURATION_CHANGED", assertThrows(IllegalStateException.class,
        () -> current.prepareGeneration(original)).getMessage());
  }

  Path legacyAttempt(UUID job, Speaker speaker) throws Exception {
    Path attempt = Files.createDirectories(data.resolve(job.toString()).resolve(UUID.randomUUID().toString()));
    Files.writeString(attempt.resolve("render-config.json"), json.writeValueAsString(Map.of(
        "model", MODEL, "voice", OpenAiSpeechSettings.DEFAULT.voice(speaker), "speed", .95,
        "group", Map.of("speaker", speaker.name()))));
    Files.write(attempt.resolve("stream.pcm"), new byte[] {1, 0});
    return attempt;
  }

  @Test
  void legacyDefaultAttemptsAreVerifiedAndLeftUnchanged() throws Exception {
    UUID job = UUID.randomUUID();
    Path host = legacyAttempt(job, Speaker.HOST), expert = legacyAttempt(job, Speaker.EXPERT);
    String before = Files.readString(host.resolve("render-config.json"));
    var modified = Files.getLastModifiedTime(host.resolve("render-config.json"));
    adapter(OpenAiSpeechSettings.DEFAULT).prepareGeneration(job);
    assertTrue(Files.exists(snapshot(job)));
    assertEquals(before, Files.readString(host.resolve("render-config.json")));
    assertEquals(modified, Files.getLastModifiedTime(host.resolve("render-config.json")));
    assertArrayEquals(new byte[] {1, 0}, Files.readAllBytes(expert.resolve("stream.pcm")));
  }

  @Test
  void legacyJobCannotAdoptNewSettingsEvenIfOnlyOneRoleHasPriorAudio() throws Exception {
    UUID job = UUID.randomUUID();
    legacyAttempt(job, Speaker.HOST);
    assertEquals("SPEECH_CONFIGURATION_UNVERIFIED", assertThrows(IllegalStateException.class,
        () -> adapter(new OpenAiSpeechSettings("marin", "coral", .95)).prepareGeneration(job))
        .getMessage());
    assertFalse(Files.exists(snapshot(job)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"host", "expert", "speed", "model"})
  void verifiedLegacySettingsMismatchIsDistinctAndPreservesEvidence(String field) throws Exception {
    UUID job = UUID.randomUUID();
    Path host = legacyAttempt(job, Speaker.HOST), expert = legacyAttempt(job, Speaker.EXPERT);
    String before = Files.readString(host.resolve("render-config.json"));
    var modified = Files.getLastModifiedTime(host.resolve("render-config.json"));
    var changed = new OpenAiSpeech(json, "", data.toString(),
        field.equals("model") ? "another-model" : MODEL,
        new OpenAiSpeechSettings(field.equals("host") ? "cedar" : "marin",
            field.equals("expert") ? "coral" : "ash", field.equals("speed") ? 1.2 : .95));
    assertEquals("SPEECH_CONFIGURATION_MISMATCH", assertThrows(IllegalStateException.class,
        () -> changed.prepareGeneration(job)).getMessage());
    assertFalse(Files.exists(snapshot(job)));
    assertEquals(before, Files.readString(host.resolve("render-config.json")));
    assertEquals(modified, Files.getLastModifiedTime(host.resolve("render-config.json")));
    assertArrayEquals(new byte[] {1, 0}, Files.readAllBytes(expert.resolve("stream.pcm")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missingSpeed", "missingConfig", "malformed", "empty"})
  void incompleteLegacyEvidenceRemainsUnverifiedEvenWithAKnownDifference(String problem)
      throws Exception {
    UUID job = UUID.randomUUID();
    legacyAttempt(job, Speaker.HOST);
    Path config = legacyAttempt(job, Speaker.EXPERT).resolve("render-config.json");
    switch (problem) {
      case "missingSpeed" -> {
        var saved = (ObjectNode) json.readTree(Files.readString(config));
        saved.remove("speed");
        Files.writeString(config, saved.toString());
      }
      case "missingConfig" -> Files.delete(config);
      case "malformed" -> Files.writeString(config, "{");
      case "empty" -> Files.writeString(config, "");
    }
    assertEquals("SPEECH_CONFIGURATION_UNVERIFIED", assertThrows(IllegalStateException.class,
        () -> adapter(new OpenAiSpeechSettings("cedar", "ash", .95)).prepareGeneration(job))
        .getMessage());
    assertFalse(Files.exists(snapshot(job)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missingSpeed", "missingConfig", "malformed", "empty", "speed", "voice", "model"})
  void legacyMissingOrIncompatibleEvidenceFailsClosed(String problem) throws Exception {
    UUID job = UUID.randomUUID();
    Path config = legacyAttempt(job, Speaker.EXPERT).resolve("render-config.json");
    var saved = (ObjectNode) json.readTree(Files.readString(config));
    switch (problem) {
      case "missingSpeed" -> { saved.remove("speed"); Files.writeString(config, saved.toString()); }
      case "missingConfig" -> Files.delete(config);
      case "malformed" -> Files.writeString(config, "{");
      case "empty" -> Files.writeString(config, "");
      case "speed" -> Files.writeString(config, saved.put("speed", 1.2).toString());
      case "voice" -> Files.writeString(config, saved.put("voice", "coral").toString());
      case "model" -> Files.writeString(config, saved.put("model", "different").toString());
    }
    boolean changed = Set.of("speed", "voice", "model").contains(problem);
    assertEquals(changed ? "SPEECH_CONFIGURATION_MISMATCH" : "SPEECH_CONFIGURATION_UNVERIFIED",
        assertThrows(IllegalStateException.class,
            () -> adapter(OpenAiSpeechSettings.DEFAULT).prepareGeneration(job)).getMessage());
    assertFalse(Files.exists(snapshot(job)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{", "null", "[]"})
  void corruptGenerationSnapshotIsNeverReplaced(String saved) throws Exception {
    UUID job = UUID.randomUUID();
    Files.createDirectories(snapshot(job).getParent());
    Files.writeString(snapshot(job), saved);
    assertEquals("SPEECH_CONFIGURATION_UNVERIFIED", assertThrows(IllegalStateException.class,
        () -> adapter(OpenAiSpeechSettings.DEFAULT).prepareGeneration(job)).getMessage());
    assertEquals(saved, Files.readString(snapshot(job)));
  }
}
