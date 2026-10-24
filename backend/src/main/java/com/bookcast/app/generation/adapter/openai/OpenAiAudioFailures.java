package com.bookcast.app.generation.adapter.openai;

import com.bookcast.app.generation.application.Capabilities.Metadata;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.concurrent.*;

/**
 * Converts OpenAI error events and transport exceptions into Bookcast-owned failure metadata.
 *
 * <p>Used by both the speech and text adapters. Classification relies on structured provider types
 * and codes, HTTP statuses and exception types rather than human-readable messages. Only bounded,
 * sanitized provider codes are retained; provider messages and raw response bodies are not copied
 * into the metadata. Usage and cost remain unknown here because these error inputs do not establish
 * them.
 *
 * <p>This class classifies failures and parses retry hints. The application retry policy decides
 * whether another attempt is allowed and how long to wait; these methods do not perform retries.
 */
final class OpenAiAudioFailures {
  private static final Set<Integer> TRANSIENT_HTTP = Set.of(429, 500, 502, 503, 504);
  private static final long MAX_RETRY_AFTER_MS = 86_400_000L;

  /**
   * Classifies the error object inside a provider event or response.
   *
   * <p>Known quota and billing codes or types take precedence and are never retryable, including
   * when the code conflicts with a transient type. Otherwise the provider type determines the
   * category. If the type is absent or invalid, classification falls back to recognized codes.
   * An unrecognized type does not acquire retryability from a transient-looking code. Recognized
   * transient categories receive an HTTP status classification even without an HTTP response.
   *
   * @param error the provider's error object, not the enclosing event or response
   * @return safe failure metadata with unknown usage and cost
   */
  static Metadata event(JsonNode error) {
    String code = safeCode(error.path("code").asText());
    String type = safeCode(error.path("type").asText());
    if (isQuota(code) || isQuota(type))
      return failure("PROVIDER_QUOTA", false, null, code, null);
    return switch (type) {
      case "rate_limit_error" -> failure("PROVIDER_RATE_LIMIT", true, 429, code, null);
      case "server_error" -> failure("PROVIDER_SERVER_ERROR", true, 500, code, null);
      case "service_unavailable_error" ->
          failure("PROVIDER_SERVER_ERROR", true, 503, code, null);
      case null -> failure(code);
      default -> failure("PROVIDER_ERROR", false, null, code, null);
    };
  }

  /**
   * Classifies a failed provider call, unwrapping asynchronous completion exceptions first.
   *
   * <p>WebSocket handshake failures retain the HTTP status and parsed Retry-After hint; only the
   * explicitly supported transient statuses are retryable. Timeouts and other I/O failures are
   * retryable, while TLS configuration, protocol and unrecognized adapter failures are not.
   * Interrupted calls restore the current thread's interrupt flag and are non-retryable.
   *
   * @param error the transport or adapter exception, possibly wrapped by an asynchronous operation
   * @return safe failure metadata without exception messages, response bodies or assumed usage
   */
  static Metadata exception(Throwable error) {
    while ((error instanceof ExecutionException || error instanceof CompletionException)
            && error.getCause() != null) error = error.getCause();
    if (error instanceof WebSocketHandshakeException handshake) {
      var response = handshake.getResponse();
      int status = response.statusCode();
      return failure(
              "PROVIDER_HTTP_" + status,
              TRANSIENT_HTTP.contains(status),
              status,
              null,
              retryAfter(response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
    }
    if (error instanceof TimeoutException || error instanceof HttpTimeoutException)
      return failure("PROVIDER_TIMEOUT", true, null, null, null);
    if (error instanceof javax.net.ssl.SSLHandshakeException
            || error instanceof javax.net.ssl.SSLPeerUnverifiedException)
      return failure("PROVIDER_TLS_CONFIGURATION", false, null, null, null);
    if (error instanceof java.net.ProtocolException)
      return failure("PROVIDER_PROTOCOL_FAILURE", false, null, null, null);
    if (error instanceof IOException)
      return failure("PROVIDER_TRANSPORT_FAILURE", true, null, null, null);
    if (error instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return failure("PROCESS_INTERRUPTED", false, null, null, null);
    }
    return failure("AUDIO_ADAPTER_FAILURE", false, null, null, null);
  }

  /**
   * Reads a Retry-After hint as nonnegative whole seconds or an RFC 1123 date.
   *
   * <p>Calendar dates are resolved strictly, and trailing text is rejected. Valid delays are bounded
   * to zero through 24 hours: past dates produce zero, while longer delays are capped. The application
   * retry policy decides whether a wait is acceptable.
   *
   * @param value the header value, or {@code null} when absent
   * @param now the reference time used to convert an absolute date into a delay
   * @return the delay in milliseconds, or {@code null} for an absent or invalid value, including
   *     numeric or date arithmetic that overflows milliseconds
   */
  static Long retryAfter(String value, Instant now) {
    if (value == null) return null;
    value = value.strip();
    try {
      long delay =
          value.matches("[0-9]+")
              ? Math.multiplyExact(Long.parseLong(value), 1000)
              : Duration.between(
                      now,
                      ZonedDateTime.parse(
                              value,
                              DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT))
                          .toInstant())
                  .toMillis();
      return Math.max(0, Math.min(MAX_RETRY_AFTER_MS, delay));
    } catch (NumberFormatException | DateTimeException | ArithmeticException malformed) {
      return null;
    }
  }

  private static String safeCode(String value) {
    return value.matches("[A-Za-z0-9_.-]{1,80}") ? value : null;
  }

  private static boolean isQuota(String value) {
    return switch (value) {
      case "insufficient_quota", "credit_balance_exhausted", "billing_hard_limit_reached",
              "billing_not_active", "organization_usage_limit_exceeded", "project_usage_limit_exceeded" -> true;
      case null, default -> false;
    };
  }

  /**
   * Classifies a code-only error when no usable provider type was supplied. Retains the existing
   * transient aliases and recognizes {@code server_is_overloaded}. Quota and billing aliases have
   * already been handled by {@code event}; other codes remain generic non-retryable failures.
   */
  private static Metadata failure(String providerCode) {
    return switch (providerCode) {
      case "rate_limit_exceeded", "slow_down" ->
          failure("PROVIDER_RATE_LIMIT", true, 429, providerCode, null);
      case "server_error", "internal_server_error", "service_unavailable" ->
          failure("PROVIDER_SERVER_ERROR", true, 500, providerCode, null);
      case "server_is_overloaded" ->
          failure("PROVIDER_SERVER_ERROR", true, 503, providerCode, null);
      case null, default -> failure("PROVIDER_ERROR", false, null, providerCode, null);
    };
  }

  /**
   * Builds payload-free metadata from the supplied classification, retaining the sanitized provider
   * code for diagnostics and caller-facing explanations.
   */
  private static Metadata failure(
          String code, boolean retry, Integer status, String providerCode, Long delay) {
    return new Metadata(Map.of(), null, code, retry, status, providerCode, delay);
  }
}
