package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import java.nio.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies deterministic, no-network capabilities when {@code bookcast.ai-mode=fake} is selected.
 *
 * <p>The fixed dialogue and test tone exercise the normal generation, artifact and playback paths;
 * they do not establish source fidelity or speech quality. Both capabilities report empty usage
 * maps and explicit zero cost because no paid operation occurs. They leave checkpoint persistence
 * and file handling to the same application and artifact adapters used by real generation.
 */
@Configuration
@ConditionalOnProperty(name = "bookcast.ai-mode", havingValue = "fake")
public class FakeCapabilities {
  /**
   * Supplies a fixed two-turn Finnish placeholder, retaining the request's segment order and source
   * block IDs. Source text, prior turns, language choices and first/last flags do not affect it.
   *
   * @return local text generation with the {@code fake-dialogue} model profile
   */
  @Bean
  public TextGeneration fakeText() {
    return new TextGeneration() {
      public Profile profile() {
        return new Profile("fake", "fake-dialogue");
      }

      public TextResult generate(TextRequest r) {
        return new TextResult(
            new Segment(
                r.order(),
                r.blocks().stream().map(b -> b.id()).toList(),
                List.of(
                    new Turn(Speaker.HOST, "Mikä tässä on olennaista?"),
                    new Turn(
                        Speaker.EXPERT,
                        "Tämä on maksuton paikallinen esikatselu. Oikea generaattori käsittelee lähteen tässä kohdassa."))),
            new Metadata(Map.of(), 0.0, null, false));
      }
    };
  }

  /**
   * Supplies a 440 Hz test tone as signed 16-bit little-endian mono PCM at 24,000 Hz. Duration is at
   * least six seconds, increasing with the script's whitespace-separated word count at two words per
   * second. The returned transcript is the supplied group text, rather than a measured transcription.
   * Job and attempt IDs do not affect rendering, and this capability writes no attempt files itself.
   *
   * @return local audio generation with the {@code fake-audio} model profile
   */
  @Bean
  public SpeechRendering fakeSpeech() {
    return new SpeechRendering() {
      public Profile profile() {
        return new Profile("fake", "fake-audio");
      }

      public AudioResult render(Group g, UUID job, UUID attempt) {
        int samples = Math.max(24000 * 6, g.text().split("\\s+").length * 24000 / 2);
        var buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++)
          buffer.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / 24000) * 1800));
        return new AudioResult(buffer.array(), g.text(), new Metadata(Map.of(), 0.0, null, false));
      }
    };
  }
}
