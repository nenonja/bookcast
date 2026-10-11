package com.bookcast.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.DialogueText;
import com.bookcast.app.generation.domain.Podcast.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DialogueTextTest {
  @Test
  void matchingRolePrefixesAreRemoved() {
    for (Speaker speaker : Speaker.values()) {
      for (String prefix : List.of("%s: ", "%s : ", "**%s:** ", "**%s**: ", "%s — ", "%s - ")) {
        assertEquals(
            "Hei",
            DialogueText.normalize(new Turn(speaker, prefix.formatted(speaker) + "Hei")).text());
      }
    }
    assertEquals(
        "Selitys", DialogueText.normalize(new Turn(Speaker.EXPERT, "EXPERT — Selitys")).text());
  }

  @Test
  void leadingWhitespaceIsHandled() {
    assertEquals("Hei", DialogueText.normalize(new Turn(Speaker.HOST, " \n\t HOST : Hei")).text());
  }

  @Test
  void ordinaryContentIsUnchanged() {
    for (String text :
        List.of(
            "Hei",
            "Käsitellään host tässä myöhemmin.",
            "HOST on rooli.",
            "Esimerkki: HOST: Hei",
            "  Tavallinen teksti.")) {
      var turn = new Turn(Speaker.HOST, text);
      assertSame(turn, DialogueText.normalize(turn));
    }
  }

  @Test
  void oppositeSpeakerPrefixIsRejected() {
    for (Speaker speaker : Speaker.values()) {
      String other = speaker == Speaker.HOST ? "EXPERT" : "HOST";
      assertThrows(
          DialogueText.SpeakerMismatch.class,
          () -> DialogueText.normalize(new Turn(speaker, "**" + other + "**: Hei")));
    }
  }

  @Test
  void prefixWithoutSpeechCannotBeAccepted() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DialogueText.normalize(new Turn(Speaker.HOST, "HOST: ")));
  }

  private Segment freshSegment(AudioRetryTest.Fixture f, String host) {
    // Fixture source identity comes from the existing persisted segment.
    var original = f.store.segments(f.job.id()).getFirst();
    return new Segment(
        0,
        original.coveredSourceBlockIds(),
        List.of(new Turn(Speaker.HOST, host), new Turn(Speaker.EXPERT, "EXPERT — Siksi.")));
  }

  @Test
  void normalizationPrecedesPersistenceAndTts() {
    var f = new AudioRetryTest.Fixture();
    var original = freshSegment(f, "**HOST:** Miksi?");
    f.receipts.clear();
    when(f.store.segments(any())).thenReturn(List.of());
    when(f.text.generate(any()))
        .thenReturn(new TextResult(original, new Metadata(Map.of(), 0.0, null, false)));
    when(f.speech.render(any(), any(), any()))
        .thenReturn(
            new AudioResult(new byte[] {1, 0}, "speech", new Metadata(Map.of(), 0.0, null, false)));
    f.service.workOne();
    verify(f.store)
        .segment(
            eq(f.job.id()),
            argThat(
                s ->
                    s.dialogueTurns().getFirst().text().equals("Miksi?")
                        && s.dialogueTurns().getLast().text().equals("Siksi.")));
    verify(f.speech, times(2))
        .render(
            argThat(g -> !g.text().contains("HOST") && !g.text().contains("EXPERT")),
            eq(f.job.id()),
            any());
    assertEquals("**HOST:** Miksi?", original.dialogueTurns().getFirst().text());
  }

  @Test
  void oppositePrefixBlocksPersistenceAndAudio() {
    var f = new AudioRetryTest.Fixture();
    var original = freshSegment(f, "EXPERT: Miksi?");
    f.receipts.clear();
    when(f.store.segments(any())).thenReturn(List.of());
    when(f.text.generate(any()))
        .thenReturn(new TextResult(original, new Metadata(Map.of(), 0.0, null, false)));
    f.service.workOne();
    verify(f.store, never()).segment(any(), any());
    verify(f.speech, never()).render(any(), any(), any());
    assertEquals(Status.failed, f.job.status());
    assertEquals("DIALOGUE_SPEAKER_PREFIX_MISMATCH", f.job.errorCode());
  }
}
