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

import java.time.temporal.TemporalAmount;

/**
 * A work queue for Janitor
 */

public interface JanitorQueue {
  /**
   * Put a message on the queue with a delay
   * @param message message to be placed on the queue
   * @param delay when the message will be delivered
   */

  void push(Message message, TemporalAmount delay);

  /**
   * Polls queue for a message
   * @param callback action on each polled message
   * @throws Exception
   */

  void poll(MessageCallback callback) throws Exception;

  /**
   * Queue size
   * @return queue size
   */

  int size();
}
