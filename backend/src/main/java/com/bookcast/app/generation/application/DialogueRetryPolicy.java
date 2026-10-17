package com.bookcast.app.generation.application;

import com.bookcast.app.generation.application.Capabilities.Metadata;
import java.util.Set;

/** Dialogue retries share the existing backoff timing, but not audio-specific classifications. */
public final class DialogueRetryPolicy {
  public static final int MAX_ATTEMPTS = 3;

  private DialogueRetryPolicy() {}

  public static boolean transientFailure(Metadata metadata) {
    if (!metadata.retryable() || metadata.errorCode() == null) return false;
    Integer status = metadata.providerStatus();
    if (status != null) {
      return (status == 429 || status >= 500 && status <= 599)
          && (metadata.errorCode().equals("PROVIDER_HTTP_" + status)
              || Set.of("PROVIDER_RATE_LIMIT", "PROVIDER_SERVER_ERROR")
                  .contains(metadata.errorCode()));
    }
    return Set.of(
            "PROVIDER_TIMEOUT",
            "PROVIDER_TRANSPORT_FAILURE",
            "PROVIDER_RATE_LIMIT",
            "PROVIDER_SERVER_ERROR")
        .contains(metadata.errorCode());
  }
}
