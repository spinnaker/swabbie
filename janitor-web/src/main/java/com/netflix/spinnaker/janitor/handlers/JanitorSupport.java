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

package com.netflix.spinnaker.janitor.handlers;

import com.netflix.spinnaker.janitor.model.EntityTag;
import com.netflix.spinnaker.janitor.model.ResourceTagger;
import com.netflix.spinnaker.janitor.model.Action;
import com.netflix.spinnaker.janitor.queue.CleanupMessage;
import com.netflix.spinnaker.janitor.queue.JanitorQueue;
import com.netflix.spinnaker.janitor.queue.Message;
import com.netflix.spinnaker.janitor.model.Resource;
import com.netflix.spinnaker.janitor.rulesengine.Result;
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.*;
import java.util.Comparator;

/**
 * Provides concrete marking and cleaning support for message handlers
 */

public class JanitorSupport {
  private static final Logger LOGGER = LoggerFactory.getLogger(JanitorSupport.class);

  /**
   * Cleans up a resource
   * @throws IOException
   */

  void cleanupResource(final Message message,
                       final ResourceTagger resourceTagger,
                       final Clock clock,
                       Action<Message> messageAction) throws IOException {
    final String resourceId = ((CleanupMessage) message).getResourceId();
    final String resourceName = ((CleanupMessage) message).getResourceName();
    final String region = ((CleanupMessage) message).getRegion();
    EntityTag tag = resourceTagger.find(resourceId, resourceName, message.getResourceType());
    if (tag == null || tag.getOptedOut() || !tag.getMarked()) {
      LOGGER.warn("Encountered a message for resource {} not previously seen or opted out. Skipping", resourceId);
      return;
    }

    LocalDate now = LocalDate.now(clock);
    LocalDate scheduledTerminationAt = tag.getScheduledTerminationAt();
    if (scheduledTerminationAt != null && (scheduledTerminationAt.isEqual(now) || scheduledTerminationAt.isAfter(now))) {
      messageAction.invoke(message);
      tag.setTerminatedAt(LocalDate.now(clock));
      resourceTagger.upsert(tag, resourceId, message.getResourceType(), message.getAccount(), region, message.getCloudProvider());
    }
  }

  /**
   * Marks a violating resource then schedules its deletion by placing it on the queue with a delay
   * Will skip an opted out resource. If a resource termination time is encountered,
   * the resource will be placed on the queue for instant delivery
   * @throws Exception
   */

  void markResource(final Message message,
                    final Resource resource,
                    final RulesEngine rulesEngine,
                    final ResourceTagger resourceTagger,
                    final Clock clock,
                    JanitorQueue janitorQueue) throws Exception {
    final Result result = rulesEngine.run(resource);
    EntityTag tag = resourceTagger.find(resource.getId(), resource.getName(), message.getResourceType());
    if (!result.valid()) {
      Result.Summary mostRecentSummary = result.getSummaries()
        .stream()
        .sorted(Comparator.comparing(Result.Summary::getTerminationTime).reversed())
        .findFirst()
        .orElseThrow(
          () -> new IllegalStateException("Resource was marked but not reason was provided")
        );

      if (tag == null) {
        tag = new EntityTag()
          .withName(ResourceTagger.JANITOR_PREFIX + resource.getId())
          .setMarked(mostRecentSummary.getDescription(), LocalDate.now(clock))
          .setScheduledTerminationAt(mostRecentSummary.getTerminationTime());

        scheduleResourceCleanup(message, resource, resourceTagger, clock, janitorQueue, tag, mostRecentSummary);
        return;
      }

      if (!tag.getOptedOut()) {
        if (!tag.getMarked()) {
          LOGGER.info("Resource {} hasn't been explicitly marked", resource);
          tag.setMarked(mostRecentSummary.getDescription(), LocalDate.now(clock))
            .setScheduledTerminationAt(mostRecentSummary.getTerminationTime());
        }

        scheduleResourceCleanup(message, resource, resourceTagger, clock, janitorQueue, tag, mostRecentSummary);
      }
    } else {
      LOGGER.info("Resource {} is no longer a cleanup candidate", resource);
      if (tag != null) {
        tag.setUnmarked(LocalDate.now(clock));
        resourceTagger.upsert(tag, resource.getId(), message.getResourceType(), message.getAccount(), resource.getRegion(), message.getCloudProvider());
      }
    }
  }

  /**
   * Schedules resource termination by pushing on the queue with a delay
   * The delay is calculated between now and the scheduled termination time
   * @throws IOException
   */

  private void scheduleResourceCleanup(final Message message,
                                       final Resource resource,
                                       final ResourceTagger resourceTagger,
                                       final Clock clock,
                                       JanitorQueue janitorQueue,
                                       EntityTag tag,
                                       final Result.Summary mostRecentSummary) throws IOException {
    Instant thisInstant = Instant.now(clock);
    Instant terminationInstant = mostRecentSummary.getTerminationTime().atStartOfDay(clock.getZone()).toInstant();
    Duration scheduledCleaningAtDelay = Duration.between(terminationInstant, Instant.now(clock));

    if (terminationInstant.equals(thisInstant) || thisInstant.isAfter(terminationInstant)) {
      tag.setScheduledTerminationAt(LocalDate.now(clock));
      scheduledCleaningAtDelay = Duration.ofSeconds(0);
    }

    resourceTagger.upsert(tag, resource.getId(), message.getResourceType(), message.getAccount(), resource.getRegion(), message.getCloudProvider());
    janitorQueue.push(
      new CleanupMessage(
        message.getCloudProvider(),
        message.getResourceType(),
        message.getAccount(),
        resource.getId(),
        resource.getName(),
        resource.getRegion()
      ),
      scheduledCleaningAtDelay
    );
  }
}
