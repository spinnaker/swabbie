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

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
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
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val applicationEventPublisher: ApplicationEventPublisher
): ResourceHandler {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration) {
    try {
      log.info("getting upstream resources of type {}", workConfiguration.resourceType)
      getUpstreamResources(workConfiguration)?.let { upstreamResources ->
        log.info("fetched {} upstream resources of type {}, dryRun {}",
          upstreamResources.size, workConfiguration.resourceType, workConfiguration.dryRun)
        upstreamResources.filter {
          !it.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions)
        }.forEach { upstreamResource ->
          rules
            .filter { it.applies(upstreamResource) }
            .mapNotNull {
              it.apply(upstreamResource).summary
            }.let { violationSummaries ->
              resourceTrackingRepository.find(
                resourceId = upstreamResource.resourceId,
                namespace = workConfiguration.namespace
              ).let { trackedMarkedResource ->
                if (trackedMarkedResource != null && violationSummaries.isEmpty() && !workConfiguration.dryRun) {
                  log.info("forgetting now valid {} resource", upstreamResource)
                  resourceTrackingRepository.remove(trackedMarkedResource)
                  applicationEventPublisher.publishEvent(UnMarkResourceEvent(trackedMarkedResource, workConfiguration))
                } else if (!violationSummaries.isEmpty()) {
                  log.info("found cleanup candidate {} of type {}, violations {}, dryRyn {}",
                    upstreamResource, workConfiguration.resourceType, violationSummaries, workConfiguration.dryRun)
                  LocalDate.now(clock).with { today ->
                    today.plus(
                      Period.ofDays(workConfiguration.retentionDays)
                    )
                  }.let { projectedDeletionDate ->
                      log.info("Projected deletion date for resource {} is {}, dryRun {}", projectedDeletionDate, upstreamResource, workConfiguration.dryRun)
                      (trackedMarkedResource?.copy(
                        projectedDeletionStamp = projectedDeletionDate.atStartOfDay(clock.zone).toInstant().toEpochMilli(),
                        summaries = violationSummaries
                      ) ?: MarkedResource(
                        resource = upstreamResource,
                        summaries = violationSummaries,
                        namespace = workConfiguration.namespace,
                        projectedDeletionStamp = projectedDeletionDate.atStartOfDay(clock.zone).toInstant().toEpochMilli()
                      )
                    ).let {
                        if (!workConfiguration.dryRun) {
                          resourceTrackingRepository.upsert(it)
                          if (trackedMarkedResource == null) {
                            applicationEventPublisher.publishEvent(MarkResourceEvent(it, workConfiguration))
                          }
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
  override fun clean(markedResource: MarkedResource, workConfiguration: WorkConfiguration) {
    getUpstreamResource(markedResource, workConfiguration)
      ?.takeIf { !it.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions) }
      ?.let { upstreamResource ->
        rules
          .filter { it.applies(upstreamResource) }
          .mapNotNull {
            it.apply(upstreamResource).summary
          }.let { violationSummaries ->
            if (violationSummaries.isEmpty() && !workConfiguration.dryRun) {
              applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
              resourceTrackingRepository.remove(markedResource)
            } else {
              // adjustedDeletionStamp is the adjusted projectedDeletionStamp after notification is sent
              log.info("Preparing deletion of {}. dryRun {}", markedResource, workConfiguration.dryRun)
              if (markedResource.adjustedDeletionStamp != null && !workConfiguration.dryRun) {
                remove(markedResource, workConfiguration)
                resourceTrackingRepository.remove(markedResource)
                applicationEventPublisher.publishEvent(DeleteResourceEvent(markedResource, workConfiguration))
              }
            }
          }
      }
  }

  abstract fun remove(markedResource: MarkedResource, workConfiguration: WorkConfiguration)
}
