package com.bookcast.app.generation.adapter;

import com.bookcast.app.generation.application.AudioRetryPolicy.Timing;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class AudioRetryTiming implements Timing {
  public double jitter() {
    return ThreadLocalRandom.current().nextDouble();
  }

  public void pause(long milliseconds) throws InterruptedException {
    Thread.sleep(milliseconds);
  }
}
