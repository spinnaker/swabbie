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

import com.netflix.spinnaker.janitor.model.ResourceTagger;
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine;
import java.time.Clock;

/**
 * A handler playing a role of a Janitor.
 * Each handler is capable of either marking/unmarking of a resource.
 * Each handler can remove a particular resource as well
 */

public interface MessageHandler {
  /**
   * Determines if a handler can handle this message
   * @param message message that can be handled by handlers
   * @return
   */

  boolean supports(Message message);

  /**
   * Handles marking of resources
   * @param message message with marking context
   * @param rulesEngine a rules engine to check the validity of a resource
   * @param resourceTagger a service to tag marked resources
   * @param clock a system clock to keep message delivery in sync
   * @param janitorQueue the work queue - needed when scheduling a cleanup of a resource
   * @throws Exception
   */

  void handleMark(Message message, RulesEngine rulesEngine, ResourceTagger resourceTagger, Clock clock, JanitorQueue janitorQueue) throws Exception;

  /**
   * Handles cleaning of a resource
   * @param message contains cleaning context and info on which resource to cleanup
   * @param resourceTagger a service to tag marked resources
   * @param clock a system clock to keep message delivery in sync
   * @throws Exception
   */

  void handleCleanup(Message message, ResourceTagger resourceTagger, Clock clock) throws Exception;
}
