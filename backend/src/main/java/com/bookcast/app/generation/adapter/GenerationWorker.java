package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.GenerationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "bookcast.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GenerationWorker {
  private final GenerationService service;
  private volatile boolean ready;

  public GenerationWorker(GenerationService service) {
    this.service = service;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void ready() {
    service.interrupted();
    ready = true;
  }

  @Scheduled(fixedDelay = 1000)
  public void run() {
    if (ready) service.workOne();
  }
}
