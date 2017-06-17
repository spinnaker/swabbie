/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.janitor.queue.memory;

import com.netflix.spinnaker.janitor.queue.Message;
import com.netflix.spinnaker.janitor.queue.JanitorQueue;
import com.netflix.spinnaker.janitor.queue.MessageCallback;
import com.netflix.spinnaker.janitor.queue.ScheduledAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.extra.Temporals;

import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * In Memory Impl of a JanitorQueue
 * Used for local development
 */

public class InMemoryQueue implements JanitorQueue, Closeable {
  private static Logger LOGGER = LoggerFactory.getLogger(InMemoryQueue.class);
  private TemporalAmount ackTimeout;
  private Queue<Envelope> workQueue;
  private Queue<Envelope> waitQueue;
  private ScheduledAction scheduledAction;
  private Clock clock;

  public InMemoryQueue(final Clock clock,
                       TemporalAmount ackTimeout,
                       long initialRedeliveryDelayInSeconds,
                       long redeliveryDelayInSeconds,
                       int redeliveryCount) {
    this.clock = clock;
    this.ackTimeout = ackTimeout;
    this.workQueue = new DelayQueue<>();
    this.waitQueue = new DelayQueue<>();
    this.scheduledAction = new ScheduledAction(
      new Redeliverer<>(waitQueue, workQueue, redeliveryCount),
      initialRedeliveryDelayInSeconds,
      redeliveryDelayInSeconds
    );
  }

  @Override
  public void push(Message message, TemporalAmount delay) {
    workQueue.add(new Envelope(message, clock.instant().plus(delay), clock));
  }

  @Override
  public void poll(MessageCallback fn) throws Exception {
    Envelope envelope = workQueue.poll();
    if (envelope == null || envelope.getPayload() == null) {
      LOGGER.info("{} work queue is empty.", InMemoryQueue.class.getSimpleName());
      return;
    }

    Envelope copy = (Envelope) envelope.clone();
    copy.setScheduledTime(clock.instant().plus(ackTimeout));
    waitQueue.add(copy);
    fn.accept(envelope.getPayload(), ack());
  }

  /**
   * Acknowledging a message by removing it from the wait queue
   * @return true if message was acked and false if message was already acked or doesnt exist in wait queue
   */

  private Function<Message, Boolean> ack() {
    return (message) -> {
      boolean acked = waitQueue.removeIf(it -> it.getPayload().getId().equals(message.getId()));
      LOGGER.info("acknowledging message {} result {}", message, acked);
      return acked;
    };
  }

  @Override
  public int size() {
    return workQueue.size();
  }

  @Override
  @PreDestroy
  public void close() throws IOException {
    scheduledAction.close();
  }

  /**
   * Used to deliver messages from wait queue to work queue
   * @param <T>
   */

  private static class Redeliverer<T extends Envelope> implements Runnable {
    private Queue<T> from;
    private Queue<T> to;
    private int count;

    Redeliverer(Queue<T> from, Queue<T> to, int count) {
      this.from = from;
      this.to = to;
      this.count = count;
    }

    private void pollAll(Queue<T> queue, Consumer<T> consumer) {
      boolean done = false;
      while (!done) {
        T envelope = queue.poll();
        if (envelope == null) {
          done = true;
        } else {
          consumer.accept(envelope);
        }
      }
    }

    @Override
    public void run() {
      pollAll(from, envelope -> {
        if (envelope.getCount() < count) {
          LOGGER.info("Redelivering message {} count {} max {}", envelope.getPayload().getId(), envelope.getCount(), count);
          envelope.incrementDeliveryCount();
          to.add(envelope);
          from.remove(envelope);
        }
      });
    }
  }

  /**
   * Message envelope
   */

  private static class Envelope implements Delayed, Cloneable {
    private UUID id;
    private Message payload;
    private Instant scheduledTime;
    private Clock clock;
    private int count;

    Envelope(Message payload, Instant scheduledTime, Clock clock) {
      this.id = UUID.randomUUID();
      this.payload = payload;
      this.clock = clock;
      this.scheduledTime = scheduledTime;
    }

    public UUID getId() {
      return id;
    }

    public void setId(UUID id) {
      this.id = id;
    }

    Message getPayload() {
      return payload;
    }

    int getCount() {
      return count;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return clock.instant().until(scheduledTime, Temporals.chronoUnit(unit));
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof Envelope && id.equals(((Envelope) obj).getId());
    }

    @Override
    public int compareTo(Delayed other) {
      return ((Long) getDelay(TimeUnit.MILLISECONDS))
        .compareTo(other.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
      return super.clone();
    }

    void incrementDeliveryCount() {
      this.count++;
    }

    public void setScheduledTime(Instant scheduledTime) {
      this.scheduledTime = scheduledTime;
    }
  }
}
