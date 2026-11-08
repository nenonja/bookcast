package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.GenerationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Connects Spring startup and scheduling to the generation application's persisted work queue.
 *
 * <p>Startup recovery marks previously running work interrupted before polling is enabled; it does
 * not requeue that work or repeat an uncertain provider call. Each scheduled invocation delegates
 * at most one queued job to the service, which owns checkpoints, attempts and retries. This worker
 * assumes the application's supported single-backend deployment, rather than coordinating recovery
 * across backend instances.
 */
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

  /**
   * Enables polling only after interruption recovery returns successfully. The volatile readiness
   * flag publishes completion to the scheduler; an initial recovery failure leaves polling disabled.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void ready() {
    service.interrupted();
    ready = true;
  }

  /**
   * Processes a queued job synchronously when startup recovery has completed. The configured delay
   * is one second after an invocation finishes, rather than a deadline for generation to complete.
   */
  @Scheduled(fixedDelay = 1000)
  public void run() {
    if (ready) service.workOne();
  }
}
