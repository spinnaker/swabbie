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
      getUpstreamResources(scopeOfWorkConfiguration)?.let { upstreamResources ->
        log.info("fetched {} upstream resources of type {}, dryRun {}",
          upstreamResources.size, scopeOfWorkConfiguration.resourceType, scopeOfWorkConfiguration.dryRun)
        upstreamResources.forEach { upstreamResource ->
          rules
            .filter { it.applies(upstreamResource) }
            .mapNotNull {
              it.apply(upstreamResource).summary
            }.let { violationSummaries ->
              resourceTrackingRepository.find(
                resourceId = upstreamResource.resourceId,
                namespace = scopeOfWorkConfiguration.namespace
              ).let { trackedMarkedResource ->
                if (trackedMarkedResource != null && violationSummaries.isEmpty() && !scopeOfWorkConfiguration.dryRun) {
                  log.info("forgetting now valid {} resource", upstreamResource)
                  resourceTrackingRepository.remove(trackedMarkedResource)
                  applicationEventPublisher.publishEvent(UnMarkResourceEvent(trackedMarkedResource))
                } else if (!violationSummaries.isEmpty()) {
                  log.info("found cleanup candidate {} of type {}, violations {}, dryRyn {}",
                    upstreamResource, scopeOfWorkConfiguration.resourceType, violationSummaries, scopeOfWorkConfiguration.dryRun)
                  LocalDate.now(clock).with { today ->
                    today.plus(
                      Period.ofDays(scopeOfWorkConfiguration.retention.days + scopeOfWorkConfiguration.retention.ageThresholdDays)
                    )
                  }.let { projectedDeletionDate ->
                      log.info("Projected deletion date for resource {} is {}, dryRun {}", projectedDeletionDate, upstreamResource, scopeOfWorkConfiguration.dryRun)
                      (trackedMarkedResource?.copy(
                        projectedDeletionStamp = projectedDeletionDate.atStartOfDay(clock.zone).toInstant().toEpochMilli(),
                        summaries = violationSummaries
                      ) ?: MarkedResource(
                        resource = upstreamResource,
                        summaries = violationSummaries,
                        namespace = scopeOfWorkConfiguration.namespace,
                        projectedDeletionStamp = projectedDeletionDate.atStartOfDay(clock.zone).toInstant().toEpochMilli()
                      )
                    ).let {
                        if (!scopeOfWorkConfiguration.dryRun) {
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
  override fun clean(markedResource: MarkedResource, scopeOfWorkConfiguration: ScopeOfWorkConfiguration) {
    getUpstreamResource(markedResource)
      ?.let { upstreamResource ->
        rules
          .filter { it.applies(upstreamResource) }
          .mapNotNull {
            it.apply(upstreamResource).summary
          }.let { violationSummaries ->
            if (violationSummaries.isEmpty() && !scopeOfWorkConfiguration.dryRun) {
              applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource))
              resourceTrackingRepository.remove(markedResource)
            } else {
              // adjustedDeletionStamp is the adjusted projectedDeletionStamp after notification is sent
              log.info("Preparing deletion of {}. dryRun {}", markedResource, scopeOfWorkConfiguration.dryRun)
              if (markedResource.adjustedDeletionStamp != null && !scopeOfWorkConfiguration.dryRun) {
                resourceTrackingRepository.remove(markedResource)
                applicationEventPublisher.publishEvent(NotifyOwnerEvent(markedResource))
                doDelete(markedResource)
              }
            }
          }
      }
  }

  abstract fun doDelete(markedResource: MarkedResource)
}
