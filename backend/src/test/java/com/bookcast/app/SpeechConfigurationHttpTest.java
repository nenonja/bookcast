package com.bookcast.app;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bookcast.app.generation.adapter.GenerationController;
import com.bookcast.app.generation.application.GenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpeechConfigurationHttpTest {
  @ParameterizedTest
  @ValueSource(strings = {"SPEECH_CONFIGURATION_CHANGED", "SPEECH_CONFIGURATION_MISMATCH", "SPEECH_CONFIGURATION_UNVERIFIED"})
  void resumeExplainsConfigurationFailureWithoutExposingDiagnostics(String code) throws Exception {
    var service = mock(GenerationService.class);
    UUID job = UUID.randomUUID();
    when(service.retry(job)).thenThrow(new IllegalStateException(code));
    var mvc = MockMvcBuilders
        .standaloneSetup(new GenerationController(service, new ObjectMapper()))
        .setControllerAdvice(new HttpErrors()).build();
    mvc.perform(post("/api/v1/generations/{id}/retry", job))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.message").value(code.equals("SPEECH_CONFIGURATION_UNVERIFIED")
            ? "Cannot verify this generation's speech settings. Check its saved configuration before resuming."
            : "Speech settings differ from this generation. Restore its original model, voices and speed before resuming."))
        .andExpect(jsonPath("$.diagnostics").doesNotExist());
    verify(service).retry(job);
    verifyNoMoreInteractions(service);
  }

  @Test
  void unexpectedExceptionDetailsRemainPrivate() throws Exception {
    var service = mock(GenerationService.class);
    UUID job = UUID.randomUUID();
    when(service.retry(job)).thenThrow(new IllegalStateException(
        "private API key and provider body in /private/attempt/render-config.json"));
    var mvc = MockMvcBuilders
        .standaloneSetup(new GenerationController(service, new ObjectMapper()))
        .setControllerAdvice(new HttpErrors()).build();
    mvc.perform(post("/api/v1/generations/{id}/retry", job))
        .andExpect(status().isConflict())
        .andExpect(content().json("""
            {"code":"CONFLICT","message":"This operation is unavailable in the current state."}
            """, true));
  }
}
