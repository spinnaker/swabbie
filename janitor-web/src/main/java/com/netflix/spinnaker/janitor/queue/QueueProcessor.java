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

package com.netflix.spinnaker.janitor.queue;

import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.janitor.model.ResourceTagger;
import com.netflix.spinnaker.janitor.handlers.MessageHandlerNotFoundException;
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Component
public class QueueProcessor extends DiscoveryActivated implements WorkProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueueProcessor.class);
  private final List<MessageHandler> messageHandlers;
  private final RulesEngine rulesEngine;

  private ResourceTagger resourceTagger;
  private JanitorQueue janitorQueue;
  private Executor executor;
  private Registry registry;
  private Clock clock;

  private Id opsRateId;
  private Id errorRateId;

  @Autowired
  public QueueProcessor(JanitorQueue janitorQueue,
                        Registry registry,
                        Executor messageHandlerPool,
                        Clock clock,
                        ResourceTagger resourceTagger,
                        List<MessageHandler> messageHandlers,
                        RulesEngine rulesEngine) {
    this.janitorQueue = janitorQueue;
    this.resourceTagger = resourceTagger;
    this.registry = registry;

    this.clock = clock;
    this.executor = messageHandlerPool;
    this.messageHandlers = messageHandlers;
    this.opsRateId = registry.createId("janitor.worker.opsRate");
    this.errorRateId = registry.createId("janitor.worker.errorRate");
    this.rulesEngine = rulesEngine;
  }

  @Scheduled(fixedDelay = 50000)
  public void poll() {
    if (getEnabled()) {
      try {
        registry.counter(opsRateId).increment();
        janitorQueue.poll(this::process);
      } catch (Exception e) {
        registry.counter(errorRateId).increment();
        LOGGER.error("error polling message", e);
      }
    }
  }

  public void process(Message message, Function<Message, Boolean> ack) {
    message.match(
      defaultJanitor(ack, getMessageHandler(message, messageHandlers))
    );
  }

  private Janitor defaultJanitor(Function<Message, Boolean> ack, MessageHandler handler) {
    return new Janitor() {
      @Override
      public void mark(Message message) {
        executor.execute(() -> {
          try {
            handler.handleMark(message, rulesEngine, resourceTagger, clock, janitorQueue);
            ack.apply(message);
          } catch (Exception e) {
            LOGGER.error("Failed to handle message {}", message, e);
          }
        });
      }

      @Override
      public void cleanup(Message message) {
        executor.execute(() -> {
          try {
            handler.handleCleanup(message, resourceTagger, clock);
            ack.apply(message);
          } catch (Exception e) {
            LOGGER.error("Failed to handle message {}", message, e);
          }
        });
      }
    };
  }

  /**
   * Finds handler supporting this message
   * @param message a message
   * @param messageHandlers a list of known handlers
   * @return
   */

  private MessageHandler getMessageHandler(Message message, List<MessageHandler> messageHandlers) {
    return messageHandlers
      .stream()
      .filter(h -> h.supports(message))
      .findFirst()
      .orElseThrow(
        () -> new MessageHandlerNotFoundException(
          String.format("No suitable handler found for %s", message)
        )
      );
  }
}
