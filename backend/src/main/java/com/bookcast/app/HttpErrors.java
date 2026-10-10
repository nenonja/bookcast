package com.bookcast.app;

import com.bookcast.contract.model.ErrorDto;
import java.util.NoSuchElementException;
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
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class
  })
  public ResponseEntity<ErrorDto> bad() {
    return error(400, "INVALID_REQUEST", "Check the supplied fields.");
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorDto> conflict() {
    return error(409, "CONFLICT", "This operation is unavailable in the current state.");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorDto> other() {
    return error(500, "INTERNAL_FAILURE", "The operation failed.");
  }

  private ResponseEntity<ErrorDto> error(int status, String code, String message) {
    return ResponseEntity.status(status).body(new ErrorDto().code(code).message(message));
  }
}
