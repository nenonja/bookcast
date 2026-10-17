package com.bookcast.app.library.domain;

public final class LibraryProblem extends RuntimeException {
  public LibraryProblem(String code) {
    super(code);
  }
}
