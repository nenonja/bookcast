package com.bookcast.app.generation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bookcast.app.generation.application.GenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ContentDisposition;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DownloadFilenameTest {
  @TempDir Path data;

  String download(String book, String chapter) throws Exception {
    byte[] bytes = {1, 2, 3, 4};
    Path file = Files.createTempFile(data, "download-", ".m4a");
    Files.write(file, bytes);
    var service = mock(GenerationService.class);
    UUID job = UUID.randomUUID();
    when(service.downloadAudio(job))
        .thenReturn(new GenerationService.DownloadAudio(file.toString(), book, chapter));
    var mvc = MockMvcBuilders
        .standaloneSetup(new GenerationController(service, new ObjectMapper())).build();
    var result = mvc.perform(get("/api/v1/generations/{id}/audio/download", job))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "audio/mp4"))
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(content().bytes(bytes)).andReturn();
    var disposition = ContentDisposition.parse(result.getResponse().getHeader("Content-Disposition"));
    assertEquals("attachment", disposition.getType());
    assertEquals(StandardCharsets.UTF_8, disposition.getCharset());
    assertArrayEquals(bytes, Files.readAllBytes(file));
    verify(service).downloadAudio(job);
    verifyNoMoreInteractions(service);
    return disposition.getFilename();
  }

  static Stream<Arguments> names() {
    return Stream.of(
        Arguments.of("Älykäs kirja", "Luku: käyttöoikeudet/ääni",
            "Älykäs kirja - Luku_ käyttöoikeudet_ääni.m4a"),
        Arguments.of("Kirja", "Ääni \"ää\" \\ / : * ? < > |",
            "Kirja - Ääni _ää_ _ _ _ _ _ _ _ _.m4a"),
        Arguments.of("Kirja", "Äänet\nja\tpolut", "Kirja - Äänet_ja_polut.m4a"),
        Arguments.of("Kirja", "Ääni\u200Dja\u202Epolut", "Kirja - Ääni\u200Dja\u202Epolut.m4a"),
        Arguments.of(null, null, "Chapter.m4a"),
        Arguments.of("  ", "  Ääni  ", "Ääni.m4a"),
        Arguments.of("Kirja", "  ", "Kirja - Chapter.m4a"),
        Arguments.of("", "CON", "Chapter.m4a"));
  }

  @ParameterizedTest
  @MethodSource("names")
  void commonsRulesAndSpringHeaderEncodingProduceTheExpectedName(String book, String chapter,
      String expected) throws Exception {
    assertEquals(expected, download(book, chapter));
  }

  @Test
  void completeNameIsBoundedWhileFinnishTextAndM4aExtensionSurvive() throws Exception {
    String filename = download("Ää".repeat(100), "Öö".repeat(100));
    assertEquals(255, filename.length());
    assertTrue(filename.startsWith("Ää".repeat(100) + " - "));
    assertTrue(filename.endsWith(".m4a"));
    assertTrue(filename.contains("Öö"));
  }

  @Test
  void nameWhichCannotBeConvertedStillDeliversAudioWithAFallbackName() throws Exception {
    assertEquals("Chapter.m4a", download("Kirja.", "Ää".repeat(200)));
  }
}
