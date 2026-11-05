package com.bookcast.app.generation.adapter.openai;

import com.bookcast.app.generation.domain.Podcast.Speaker;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Immutable Realtime speech settings. Speed is a multiplier in the provider's inclusive 0.25–1.5
 * range, rather than a playback setting. Voice names must be nonblank; provider availability is
 * checked by OpenAI, without a local voice allowlist. Domain speaker roles remain vendor-independent.
 */
@ConfigurationProperties("bookcast.speech")
public record OpenAiSpeechSettings(
    @DefaultValue("marin") String hostVoice,
    @DefaultValue("ash") String expertVoice,
    @DefaultValue("0.95") double speed) {
  static final OpenAiSpeechSettings DEFAULT = new OpenAiSpeechSettings("marin", "ash", .95);

  public OpenAiSpeechSettings {
    if (hostVoice == null || hostVoice.isBlank() || expertVoice == null || expertVoice.isBlank())
      throw new IllegalArgumentException("INVALID_SPEECH_VOICE");
    if (!Double.isFinite(speed) || speed < .25 || speed > 1.5)
      throw new IllegalArgumentException("INVALID_SPEECH_SPEED");
  }

  public String voice(Speaker speaker) {
    return switch (speaker) {
      case HOST -> hostVoice;
      case EXPERT -> expertVoice;
    };
  }
}
