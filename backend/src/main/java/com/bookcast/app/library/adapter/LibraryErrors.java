package com.bookcast.app.library.adapter;

import com.bookcast.app.library.domain.LibraryProblem;
import com.bookcast.contract.model.ErrorDto;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LibraryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LibraryErrors {
  @ExceptionHandler(LibraryProblem.class)
  public ResponseEntity<ErrorDto> library(LibraryProblem e) {
    return switch (e.getMessage()) {
      case "CHAPTER_NUMBER_CONFLICT" ->
          error(409, e.getMessage(), "This book already has a chapter with that number.");
      case "INVALID_CHAPTER_NUMBER" ->
          error(400, e.getMessage(), "Chapter number must be a positive integer.");
      case "BOOK_REQUIRED" -> error(400, e.getMessage(), "Select a book.");
      default -> error(400, "TITLE_REQUIRED", "Enter a title of 1 to 200 characters.");
    };
  }

  private ResponseEntity<ErrorDto> error(int status, String code, String message) {
    return ResponseEntity.status(status).body(new ErrorDto().code(code).message(message));
  }
}
