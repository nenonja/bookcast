package com.bookcast.app.generation.application;

import com.bookcast.app.generation.application.Capabilities.Metadata;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Uses Resilience4j's standard randomized exponential backoff for recorded generation attempts. */
@Component
public class AudioRetryPolicy {
  public static final int MAX_ATTEMPTS = 3;
  private static final long MAX_RETRY_AFTER_MS = 60000;
  private final IntervalFunction backoff;

  public AudioRetryPolicy() {
    this(IntervalFunction.ofExponentialRandomBackoff());
  }

  /** Allows an alternate library backoff policy, for example zero-delay offline tests. */
  public AudioRetryPolicy(IntervalFunction backoff) {
    this.backoff = backoff;
  }

  /**
   * A single provider attempt and its completed outcome. The completion callback persists timing,
   * usage and errors with the next delay in milliseconds, or null when no retry is scheduled.
   * It is invoked once, before the library waits or returns the final result.
   */
  public record Result<T>(
      T value,
      String errorCode,
      boolean transientFailure,
      Metadata metadata,
      Consumer<Long> complete) {}

  /**
   * Executes one missing unit with at most three attempts. Exceptions are never retried; only
   * explicitly classified transient results qualify. A server wait longer than 60 seconds defers
   * the unit for manual recovery instead of retrying early. Every invocation starts a fresh retry
   * execution; accepted checkpoints are selected by the caller before entering this method.
   */
  public <T> Result<T> execute(String name, Supplier<Result<T>> attempt) {
    var config =
        RetryConfig.<Result<T>>custom()
            .maxAttempts(MAX_ATTEMPTS)
            .retryOnException(error -> false)
            .retryOnResult(result ->
                result.transientFailure()
                    && !Thread.currentThread().isInterrupted()
                    && (result.metadata().retryAfterMs() == null
                        || result.metadata().retryAfterMs() <= MAX_RETRY_AFTER_MS))
            .intervalBiFunction((number, outcome) -> {
              var result = outcome.get();
              Long hint = result.metadata().retryAfterMs();
              long delay = Math.max(backoff.apply(number), hint == null ? 0 : hint);
              result.complete().accept(delay);
              return delay;
            })
            .build();
    try {
      var result =
          Retry.of(name, config).executeSupplier(() -> {
            if (Thread.currentThread().isInterrupted())
              throw new IllegalStateException("PROCESS_INTERRUPTED");
            return attempt.get();
          });
      result.complete().accept(null);
      return result;
    } catch (RuntimeException error) {
      if (Thread.currentThread().isInterrupted())
        throw new IllegalStateException("PROCESS_INTERRUPTED");
      throw error;
    }
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
}
