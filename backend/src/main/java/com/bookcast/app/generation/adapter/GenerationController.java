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

@RestController
public class GenerationController implements GenerationApi {
  private final GenerationService service;
  private final ObjectMapper json;

  public GenerationController(GenerationService service, ObjectMapper json) {
    this.service = service;
    this.json = json;
  }

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

  public ResponseEntity<Resource> getAudio(UUID id) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("audio/mp4"))
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
        .body(new FileSystemResource(service.audioPath(id)));
  }

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
