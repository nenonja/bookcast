package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.GenerationService;
import com.bookcast.app.generation.domain.Podcast.*;
import com.bookcast.contract.api.GenerationApi;
import com.bookcast.contract.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes generation jobs and existing audio through the generated OpenAPI HTTP contract.
 *
 * <p>The application service owns enqueueing, retry/resume eligibility and artifact readiness. This
 * controller maps persisted state into polling responses and serves files without generating or
 * re-encoding audio. Shared exception handling determines HTTP errors. API cost fields sum known
 * recorded attempt costs, including failed attempts; an unknown cost is excluded from the sum and
 * makes {@code costComplete} false rather than being asserted as measured zero spend.
 */
@RestController
public class GenerationController implements GenerationApi {
  private final GenerationService service;
  private final ObjectMapper json;

  public GenerationController(GenerationService service, ObjectMapper json) {
    this.service = service;
    this.json = json;
  }

  /**
   * Combines the supplied job with separate reads of accepted segments, audio receipts and attempts.
   * Counts describe accepted checkpoints, not the number of calls. Cost completeness depends only
   * on recorded attempts and is true for an empty attempt list. These reads are not an atomic
   * progress snapshot, and the returned audio URLs do not themselves verify file availability.
   */
  private GenerationDto dto(Job j) {
    var segments = service.segments(j.id());
    var audio = service.audio(j.id());
    var attempts = service.attempts(j.id());
    double
        text =
            attempts.stream()
                .filter(a -> a.capability().equals("dialogue") && a.estimatedCost() != null)
                .mapToDouble(Attempt::estimatedCost)
                .sum(),
        speech =
            attempts.stream()
                .filter(a -> a.capability().equals("audio") && a.estimatedCost() != null)
                .mapToDouble(Attempt::estimatedCost)
                .sum();
    return new GenerationDto()
        .id(j.id())
        .chapterId(j.chapterId())
        .sourceRevisionId(j.sourceRevisionId())
        .status(GenerationDto.StatusEnum.fromValue(j.status().name()))
        .stage(j.stage())
        .completedSegments(segments.size())
        .totalSegments(j.totalSegments())
        .completedAudioGroups(audio.size())
        .totalAudioGroups(j.totalAudio())
        .generationModel(j.generationModel())
        .audioModel(j.audioModel())
        .estimatedGenerationCost(text)
        .estimatedAudioCost(speech)
        .totalEstimatedCost(text + speech)
        .costComplete(attempts.stream().allMatch(a -> a.estimatedCost() != null))
        .errorCode(j.errorCode())
        .retryable(j.retryable())
        .audioUrl(
            j.status() == Status.succeeded ? "/api/v1/generations/" + j.id() + "/audio" : null)
        .audioDownloadUrl(
            j.status() == Status.succeeded && j.audioPath() != null
                ? "/api/v1/generations/" + j.id() + "/audio/download"
                : null)
        .scriptSegments(
            segments.stream().map(s -> json.convertValue(s, ScriptSegmentDto.class)).toList())
        .attempts(attempts.stream().map(a -> json.convertValue(a, AttemptDto.class)).toList());
  }

  public ResponseEntity<GenerationDto> startGeneration(UUID chapter, StartGenerationDto r) {
    return ResponseEntity.accepted()
        .body(dto(service.start(chapter, r.getSourceRevisionId(), r.getRequestId())));
  }

  public ResponseEntity<GenerationDto> getGeneration(UUID id) {
    return ResponseEntity.ok(dto(service.get(id)));
  }

  public ResponseEntity<List<GenerationDto>> listGenerations(UUID id) {
    return ResponseEntity.ok(service.list(id).stream().map(this::dto).toList());
  }

  public ResponseEntity<GenerationDto> retryGeneration(UUID id) {
    return ResponseEntity.accepted().body(dto(service.retry(id)));
  }

  /**
   * Serves a ready job's existing media as a filesystem resource, allowing Spring MVC to handle byte
   * ranges. Uses private caching for one hour; no provider call or local encoding is performed.
   *
   * @param id non-null generation ID; the service requires succeeded status and a final audio path
   * @return existing media with {@code audio/mp4} content type
   */
  public ResponseEntity<Resource> getAudio(UUID id) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("audio/mp4"))
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
        .body(new FileSystemResource(service.audioPath(id)));
  }

  /**
   * Serves the same ready artifact as an attachment, using source metadata for a UTF-8 filename.
   * Unsafe filename characters are replaced while Finnish letters remain intact; blank chapter
   * titles fall back to {@code Chapter} and blank book titles are omitted. Uses private, no-store
   * caching and does not change attempt history or regenerate media.
   *
   * @param id non-null generation ID whose completed artifact must still be available
   * @return existing {@code audio/mp4} resource with an attachment Content-Disposition header
   */
  public ResponseEntity<Resource> downloadAudio(UUID id) {
    var audio = service.downloadAudio(id);
    String chapter = safeTitle(audio.chapterTitle());
    if (chapter.isBlank()) chapter = "Chapter";
    String book = safeTitle(audio.bookTitle());
    String filename = (book.isBlank() ? chapter : book + " - " + chapter) + ".m4a";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("audio/mp4"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(new FileSystemResource(audio.path()));
  }

  /**
   * Converts a nullable title into one filename component, replacing control/format characters,
   * quotes, backslashes and reserved path characters with underscores, then stripping edge whitespace.
   * This sanitizes the suggested download name, not the storage path used to locate the artifact.
   */
  private static String safeTitle(String title) {
    if (title == null) return "";
    var safe = new StringBuilder();
    title
        .codePoints()
        .forEach(
            c -> {
              if (Character.isISOControl(c)
                  || Character.getType(c) == Character.FORMAT
                  || c == 34
                  || c == 92
                  || "/:*?<>|".indexOf(c) >= 0) safe.append('_');
              else safe.appendCodePoint(c);
            });
    return safe.toString().strip();
  }
}
