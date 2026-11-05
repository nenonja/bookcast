package com.bookcast.app;

import com.bookcast.contract.model.ErrorDto;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class HttpErrors {
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ErrorDto> missing() {
    return error(404, "NOT_FOUND", "Resource not found.");
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class
  })
  public ResponseEntity<ErrorDto> bad() {
    return error(400, "INVALID_REQUEST", "Check the supplied fields.");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorDto> validation(MethodArgumentNotValidException e) {
    if (e.getBindingResult().hasFieldErrors("chapterNumber"))
      return error(400, "INVALID_CHAPTER_NUMBER", "Chapter number must be a positive integer.");
    return bad();
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorDto> conflict(IllegalStateException e) {
    return switch (Objects.toString(e.getMessage(), "")) {
      case "SPEECH_CONFIGURATION_CHANGED", "SPEECH_CONFIGURATION_MISMATCH" -> error(409, e.getMessage(),
          "Speech settings differ from this generation. Restore its original model, voices and speed before resuming.");
      case "SPEECH_CONFIGURATION_UNVERIFIED" -> error(409, "SPEECH_CONFIGURATION_UNVERIFIED",
          "Cannot verify this generation's speech settings. Check its saved configuration before resuming.");
      default -> error(409, "CONFLICT", "This operation is unavailable in the current state.");
    };
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorDto> other() {
    return error(500, "INTERNAL_FAILURE", "The operation failed.");
  }

  private ResponseEntity<ErrorDto> error(int status, String code, String message) {
    return ResponseEntity.status(status).body(new ErrorDto().code(code).message(message));
  }
}
