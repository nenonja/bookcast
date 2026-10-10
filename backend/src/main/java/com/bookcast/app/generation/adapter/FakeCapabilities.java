package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.Capabilities.*;
import com.bookcast.app.generation.domain.Podcast.*;
import java.nio.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "bookcast.ai-mode", havingValue = "fake")
public class FakeCapabilities {
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
