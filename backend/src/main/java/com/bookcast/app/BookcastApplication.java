package com.bookcast.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class BookcastApplication {
  public static void main(String[] args) {
    SpringApplication.run(BookcastApplication.class, args);
  }
}
