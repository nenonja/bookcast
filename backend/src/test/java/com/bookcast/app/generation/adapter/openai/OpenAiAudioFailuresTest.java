package com.bookcast.app.generation.adapter.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.bookcast.app.generation.application.AudioRetryPolicy;
import com.bookcast.app.generation.application.Capabilities.Metadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAiAudioFailuresTest {
  final ObjectMapper json = new ObjectMapper();

  @ParameterizedTest
  @CsvSource({
    "0, 0",
    "12, 12000",
    "' 12 ', 12000",
    "86400, 86400000",
    "172800, 86400000",
    "9223372036854775, 86400000",
    "'Sat, 10 Oct 2026 12:00:12 GMT', 12000",
    "'Sat, 10 Oct 2026 12:00:00 GMT', 0",
    "'Sat, 10 Oct 2026 11:59:59 GMT', 0",
    "'Mon, 12 Oct 2026 12:00:00 GMT', 86400000"
  })
  void retryAfterReadsStandardFormatsAndBoundsValidDelays(String value, long expected) {
    assertEquals(
        expected, OpenAiAudioFailures.retryAfter(value, Instant.parse("2026-10-10T12:00:00Z")));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
    " ", "invalid", "-1", "1.5", "NaN", "Infinity", "+1", "1e3",
    "9223372036854775807", "9223372036854775808", "9223372036854776",
    "Sat, 32 Oct 2026 12:00:00 GMT",
    "Sat, 31 Feb 2026 12:00:00 GMT",
    "Sat, 10 Oct 2026 11:59:59 GMT; invalid suffix"
  })
  void retryAfterIgnoresMissingAndInvalidHints(String value) {
    var now = Instant.parse("2026-10-10T12:00:00Z");
    assertNull(OpenAiAudioFailures.retryAfter(value, now));
  }

  Metadata event(String type, String code) {
    var error = json.createObjectNode();
    if (type != null) error.put("type", type);
    if (code != null) error.put("code", code);
    error.put("message", "Private provider message must not escape");
    return OpenAiAudioFailures.event(error);
  }

  @ParameterizedTest
  @CsvSource({
    "rate_limit_error, PROVIDER_RATE_LIMIT, 429",
    "server_error, PROVIDER_SERVER_ERROR, 500",
    "service_unavailable_error, PROVIDER_SERVER_ERROR, 503"
  })
  void transientTypeWorksWithMissingOrUnrecognizedCodes(
      String type, String category, int status) {
    for (String code : new String[] {null, "future_provider_code"}) {
      var m = event(type, code);
      assertEquals(category, m.errorCode());
      assertEquals(status, m.providerStatus());
      assertEquals(code, m.providerCode());
      assertTrue(m.retryable());
      assertTrue(AudioRetryPolicy.transientFailure(m));
      assertTrue(m.usage().isEmpty());
      assertNull(m.cost());
      assertFalse(m.toString().contains("Private provider message"));
    }
  }

  @Test
  void overloadUsesServiceUnavailableTypeAndRetainsSpecificCode() {
    var m = event("service_unavailable_error", "server_is_overloaded");
    assertEquals("PROVIDER_SERVER_ERROR", m.errorCode());
    assertEquals(503, m.providerStatus());
    assertEquals("server_is_overloaded", m.providerCode());
    assertTrue(AudioRetryPolicy.transientFailure(m));
  }

  @Test
  void quotaTypeStopsRetriesRegardlessOfSpecificCode() {
    for (String code :
        new String[] {null, "future_billing_code", "rate_limit_exceeded", "server_error"}) {
      var m = event("insufficient_quota", code);
      assertEquals("PROVIDER_QUOTA", m.errorCode());
      assertEquals(code, m.providerCode());
      assertNull(m.providerStatus());
      assertFalse(m.retryable());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "insufficient_quota", "credit_balance_exhausted", "billing_hard_limit_reached",
        "billing_not_active", "organization_usage_limit_exceeded", "project_usage_limit_exceeded"
      })
  void quotaCodesTakePrecedenceRegardlessOfType(String code) {
    for (String type : new String[] {
        null, "rate_limit_error", "server_error", "service_unavailable_error",
        "invalid_request_error", "future_error_type", "malformed type"
    }) {
      var m = event(type, code);
      assertEquals("PROVIDER_QUOTA", m.errorCode(), "type=" + type);
      assertEquals(code, m.providerCode());
      assertNull(m.providerStatus());
      assertNull(m.retryAfterMs());
      assertFalse(m.retryable());
      assertFalse(AudioRetryPolicy.transientFailure(m));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "insufficient_quota", "credit_balance_exhausted", "billing_hard_limit_reached",
    "billing_not_active", "organization_usage_limit_exceeded", "project_usage_limit_exceeded"
  })
  void originalQuotaTypeAliasesRemainNonRetryableWithTransientCode(String type) {
    var m = event(type, "rate_limit_exceeded");
    assertEquals("PROVIDER_QUOTA", m.errorCode());
    assertEquals("rate_limit_exceeded", m.providerCode());
    assertFalse(m.retryable());
    assertFalse(AudioRetryPolicy.transientFailure(m));
  }

  @ParameterizedTest
  @CsvSource({
    "rate_limit_exceeded, PROVIDER_RATE_LIMIT, 429",
    "slow_down, PROVIDER_RATE_LIMIT, 429",
    "server_error, PROVIDER_SERVER_ERROR, 500",
    "internal_server_error, PROVIDER_SERVER_ERROR, 500",
    "service_unavailable, PROVIDER_SERVER_ERROR, 500",
    "server_is_overloaded, PROVIDER_SERVER_ERROR, 503"
  })
  void missingTypeUsesCodeFallback(String code, String category, int status) {
    var m = event(null, code);
    assertEquals(category, m.errorCode());
    assertEquals(status, m.providerStatus());
    assertEquals(code, m.providerCode());
    assertTrue(AudioRetryPolicy.transientFailure(m));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "invalid_request_error", "authentication_error", "permission_error", "future_error_type"
      })
  void nonTransientOrUnrecognizedTypesDoNotUseTransientCodeFallback(String type) {
    for (String code : List.of("rate_limit_exceeded", "server_error", "server_is_overloaded")) {
      var m = event(type, code);
      assertEquals("PROVIDER_ERROR", m.errorCode());
      assertEquals(code, m.providerCode());
      assertFalse(m.retryable());
    }
  }

  @Test
  void missingOrMalformedClassificationFieldsRemainSafeAndUnknown() {
    for (String type : new String[] {null, "invalid type with private text"}) {
      var m = event(type, "invalid code with private text");
      assertEquals("PROVIDER_ERROR", m.errorCode());
      assertNull(m.providerCode());
      assertFalse(m.retryable());
      assertFalse(m.toString().contains("private text"));
    }
  }
}
