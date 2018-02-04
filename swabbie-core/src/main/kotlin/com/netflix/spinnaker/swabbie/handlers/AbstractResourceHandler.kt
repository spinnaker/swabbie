/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.handlers

import com.netflix.spinnaker.swabbie.ScopeOfWorkConfiguration
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.NotifyOwnerEvent
import com.netflix.spinnaker.swabbie.persistence.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.model.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.*

abstract class AbstractResourceHandler(
  private val clock: Clock,
  private val rules: List<Rule>,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val applicationEventPublisher: ApplicationEventPublisher
): ResourceHandler {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(scopeOfWorkConfiguration: ScopeOfWorkConfiguration) {
    try {
      log.info("getting upstream resources of type {}", scopeOfWorkConfiguration.resourceType)
      val currentlyMarkedResources: List<MarkedResource>? = resourceTrackingRepository.getMarkedResources()
        ?.filter {
          it.resourceType == scopeOfWorkConfiguration.resourceType
        }

      getUpstreamResources(scopeOfWorkConfiguration).let { upstreamResources ->
        if (upstreamResources == null || upstreamResources.isEmpty()) {
          log.info("Upstream resources no longer exist. Removing marked resources {}", currentlyMarkedResources)
          currentlyMarkedResources?.forEach { markedResource ->
            markedResource.let {
              resourceTrackingRepository.remove(it.resourceId)
              applicationEventPublisher.publishEvent(UnMarkResourceEvent(it))
            }
          }
        } else {
          log.info("fetched {} upstream resources of type {}", upstreamResources.size, scopeOfWorkConfiguration.resourceType)
          upstreamResources.forEach { upstreamResource ->
            rules
              .filter { it.applies(upstreamResource) }
              .mapNotNull {
                it.apply(upstreamResource).summary
              }.let { violationSummaries ->
                currentlyMarkedResources?.find { it.resourceId == upstreamResource.resourceId }.let { matchedCurrentlyMarkedResource ->
                  if (violationSummaries.isEmpty() && matchedCurrentlyMarkedResource != null) {
                    log.info("forgetting now valid {} resource", matchedCurrentlyMarkedResource)
                    resourceTrackingRepository.remove(matchedCurrentlyMarkedResource.resourceId)
                    applicationEventPublisher.publishEvent(UnMarkResourceEvent(matchedCurrentlyMarkedResource))
                  } else if (!violationSummaries.isEmpty()) {
                    log.info("found cleanup candidate {} of type {}, violations {}", upstreamResource, scopeOfWorkConfiguration.resourceType, violationSummaries)
                    LocalDate.now(clock)
                      .with { today ->
                        today.plus(Period.ofDays(scopeOfWorkConfiguration.retention.days + scopeOfWorkConfiguration.retention.ageThresholdDays))
                      }.let { projectedDeletionDate ->
                        MarkedResource(
                          resource = upstreamResource,
                          summaries = violationSummaries + (matchedCurrentlyMarkedResource?.summaries ?: mutableListOf()),
                          configurationId = scopeOfWorkConfiguration.configurationId,
                          projectedDeletionStamp = projectedDeletionDate.atStartOfDay(clock.zone).toInstant().toEpochMilli()
                        ).let {
                          resourceTrackingRepository.upsert(it)
                          applicationEventPublisher.publishEvent(MarkResourceEvent(it))
                          applicationEventPublisher.publishEvent(NotifyOwnerEvent(it))
                        }
                      }
                  }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      log.error("Failed while invoking $javaClass", e)
    }
  }

  /**
   * deletes violating resources
   */
  override fun clean(markedResource: MarkedResource) {
    getUpstreamResource(markedResource)
      ?.let { upstreamResource ->
        rules
          .filter { it.applies(upstreamResource) }
          .mapNotNull {
            it.apply(upstreamResource).summary
          }.let { violationSummaries ->
            if (violationSummaries.isEmpty()) {
              applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource))
              resourceTrackingRepository.remove(markedResource.resourceId)
            } else {
              // adjustedDeletionStamp is the adjusted projectedDeletionStamp after notification is sent
              if (markedResource.adjustedDeletionStamp != null) {
                log.info("Preparing deletion of {}", markedResource)
                resourceTrackingRepository.remove(markedResource.resourceId)
                applicationEventPublisher.publishEvent(NotifyOwnerEvent(markedResource))
                doDelete(markedResource)
              }
            }
          }
      }
  }

  abstract fun doDelete(markedResource: MarkedResource)
}
