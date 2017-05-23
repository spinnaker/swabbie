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

package com.netflix.spinnaker.janitor.config;

import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.janitor.queue.JanitorQueue;
import com.netflix.spinnaker.janitor.queue.memory.InMemoryQueue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static java.lang.String.format;

@Configuration
@ComponentScan("com.netflix.spinnaker.janitor.queue")
public class QueueConfig {

  @Bean
  Executor messageHandlerPool(Registry registry) {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(4);
    pool.setMaxPoolSize(8);
    return applyThreadPoolMetrics(registry, pool);
  }

  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }

  @Bean
  @Qualifier("janitorQueue")
  JanitorQueue inMemoryQueue(Clock clock) {
    return new InMemoryQueue(clock, Duration.ofMinutes(1),10,10,3);
  }

  private static ThreadPoolTaskExecutor applyThreadPoolMetrics(Registry registry,
                                                               ThreadPoolTaskExecutor executor) {
    BiConsumer<String, Function<ThreadPoolExecutor, Integer>> createGauge =
      (name, valueCallback) -> {
        Id id = registry
          .createId(format("threadpool.%s", name))
          .withTag("id", "messageHandler");

        registry.gauge(id, executor, ref -> valueCallback.apply(ref.getThreadPoolExecutor()));
      };

    createGauge.accept("activeCount", ThreadPoolExecutor::getActiveCount);
    createGauge.accept("maximumPoolSize", ThreadPoolExecutor::getMaximumPoolSize);
    createGauge.accept("corePoolSize", ThreadPoolExecutor::getCorePoolSize);
    createGauge.accept("poolSize", ThreadPoolExecutor::getPoolSize);
    createGauge.accept("blockingQueueSize", e -> e.getQueue().size());

    return executor;
  }
}
