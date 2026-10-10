package com.bookcast.app.generation.adapter.openai;

import com.bookcast.app.generation.application.Capabilities.Metadata;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

final class OpenAiAudioFailures {
  private static final Set<Integer> TRANSIENT_HTTP = Set.of(429, 500, 502, 503, 504);
  private static final Set<String> QUOTA =
      Set.of(
          "insufficient_quota",
          "credit_balance_exhausted",
          "billing_hard_limit_reached",
          "billing_not_active",
          "organization_usage_limit_exceeded",
          "project_usage_limit_exceeded");

  static Metadata event(JsonNode error) {
    String code = safeCode(error.path("code").asText());
    String type = safeCode(error.path("type").asText());
    if (QUOTA.contains(Objects.toString(code, "")) || QUOTA.contains(Objects.toString(type, "")))
      return failure("PROVIDER_QUOTA", false, null, code, null);
    if (Set.of("rate_limit_exceeded", "slow_down").contains(Objects.toString(code, "")))
      return failure("PROVIDER_RATE_LIMIT", true, 429, code, null);
    if (Set.of("server_error", "internal_server_error", "service_unavailable")
            .contains(Objects.toString(code, ""))
        || "server_error".equals(type))
      return failure("PROVIDER_SERVER_ERROR", true, 500, code, null);
    return failure("PROVIDER_ERROR", false, null, code, null);
  }

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

  static Long retryAfter(String value, Instant now) {
    if (value == null) return null;
    try {
      double seconds = Double.parseDouble(value.strip());
      if (!Double.isFinite(seconds) || seconds < 0) return null;
      return (long) Math.min(86400000, Math.ceil(seconds * 1000));
    } catch (NumberFormatException ignored) {
      try {
        return Math.max(
            0,
            Math.min(
                86400000,
                Duration.between(
                        now,
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant())
                    .toMillis()));
      } catch (RuntimeException malformed) {
        return null;
      }
    }
  }

  private static String safeCode(String value) {
    return value.matches("[A-Za-z0-9_.-]{1,80}") ? value : null;
  }

  private static Metadata failure(
      String code, boolean retry, Integer status, String providerCode, Long delay) {
    return new Metadata(Map.of(), null, code, retry, status, providerCode, delay);
  }
}
