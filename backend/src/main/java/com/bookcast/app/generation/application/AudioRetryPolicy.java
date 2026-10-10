package com.bookcast.app.generation.application;

import com.bookcast.app.generation.application.Capabilities.Metadata;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AudioRetryPolicy {
  public static final int MAX_ATTEMPTS = 3;

  public interface Timing {
    double jitter();

    void pause(long milliseconds) throws InterruptedException;
  }

  private final Timing timing;
  private final long firstMs, secondMs, maxMs;
  private final double jitterFraction;

  public AudioRetryPolicy(
      Timing timing,
      @Value("${bookcast.audio-retry.first-delay-ms:2000}") long firstMs,
      @Value("${bookcast.audio-retry.second-delay-ms:8000}") long secondMs,
      @Value("${bookcast.audio-retry.max-delay-ms:60000}") long maxMs,
      @Value("${bookcast.audio-retry.jitter-fraction:0.2}") double jitterFraction) {
    if (firstMs < 0 || secondMs < firstMs || maxMs <= 0 || jitterFraction < 0 || jitterFraction > 1)
      throw new IllegalArgumentException("INVALID_AUDIO_RETRY_CONFIG");
    this.timing = timing;
    this.firstMs = firstMs;
    this.secondMs = secondMs;
    this.maxMs = maxMs;
    this.jitterFraction = jitterFraction;
  }

  public static boolean transientFailure(Metadata m) {
    if (!m.retryable() || m.errorCode() == null) return false;
    if (m.providerStatus() != null && !Set.of(429, 500, 502, 503, 504).contains(m.providerStatus()))
      return false;
    return Set.of(
            "UNEXPECTED_AUDIO_STRUCTURE",
            "PROVIDER_TIMEOUT",
            "PROVIDER_TRANSPORT_FAILURE",
            "EARLY_SOCKET_CLOSE",
            "PROVIDER_RATE_LIMIT",
            "PROVIDER_SERVER_ERROR",
            "PROVIDER_HTTP_429",
            "PROVIDER_HTTP_500",
            "PROVIDER_HTTP_502",
            "PROVIDER_HTTP_503",
            "PROVIDER_HTTP_504")
        .contains(m.errorCode());
  }

  public long delay(int failedAttempt, Metadata m) {
    long base = failedAttempt == 1 ? firstMs : secondMs;
    double jitter = Math.max(0, Math.min(1, timing.jitter()));
    long backoff = (long) (base * (1 + jitterFraction * jitter));
    return Math.min(maxMs, Math.max(backoff, m.retryAfterMs() == null ? 0 : m.retryAfterMs()));
  }

  public void pause(long delay) throws InterruptedException {
    timing.pause(delay);
  }
}
