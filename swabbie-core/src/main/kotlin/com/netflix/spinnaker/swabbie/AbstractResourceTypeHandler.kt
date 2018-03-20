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

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.LongTaskTimer
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractResourceTypeHandler<out T : Resource>(
  private val registry: Registry,
  private val clock: Clock,
  private val rules: List<Rule<T>>,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val ownerResolver: OwnerResolver,
  private val applicationEventPublisher: ApplicationEventPublisher
) : ResourceTypeHandler<T> {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  private val resourcesExcludedCounter = AtomicInteger(0)

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    // initialize counters
    resourcesExcludedCounter.set(0)
    val timerId = markDurationTimer.start()
    val candidateCounter = AtomicInteger(0)
    val violationCounter = AtomicInteger(0)
    val totalResourcesVisitedCounter = AtomicInteger(0)
    try {
      log.info("${javaClass.name}: getting resources with namespace {}", workConfiguration.namespace)
      getUpstreamResources(workConfiguration)?.let { upstreamResources ->
        totalResourcesVisitedCounter.set(upstreamResources.size)
        log.info("fetched {} resources with namespace {}, dryRun {}", upstreamResources.size, workConfiguration.namespace, workConfiguration.dryRun)
        upstreamResources.filter {
          !shouldExclude(it, workConfiguration)
        }.forEach { upstreamResource ->
            rules.mapNotNull {
              it.apply(upstreamResource).summary
            }.let { violationSummaries ->
                resourceTrackingRepository.find(
                  resourceId = upstreamResource.resourceId,
                  namespace = workConfiguration.namespace
                ).let { trackedMarkedResource ->
                  if (trackedMarkedResource != null && violationSummaries.isEmpty() && !workConfiguration.dryRun) {
                    log.info("Forgetting now valid resource {}", upstreamResource)
                    resourceTrackingRepository.remove(trackedMarkedResource)
                    applicationEventPublisher.publishEvent(UnMarkResourceEvent(trackedMarkedResource, workConfiguration))
                  } else if (!violationSummaries.isEmpty() && trackedMarkedResource == null) {
                    violationCounter.addAndGet(violationSummaries.size)
                    MarkedResource(
                      resource = upstreamResource,
                      summaries = violationSummaries,
                      namespace = workConfiguration.namespace,
                      resourceOwner = ownerResolver.resolve(upstreamResource),
                      projectedDeletionStamp = workConfiguration.retentionDays.days.fromNow.atStartOfDay(clock.zone).toInstant().toEpochMilli()
                    ).let {
                      candidateCounter.incrementAndGet()
                      if (!workConfiguration.dryRun) {
                        resourceTrackingRepository.upsert(it)
                        log.info("Marking resource {} for deletion", it)
                        applicationEventPublisher.publishEvent(MarkResourceEvent(it, workConfiguration))
                      }
                    }
                  }
                }
              }
          }
      }
    } catch (e: Exception) {
      recordFailureForAction(Action.MARK, workConfiguration, e)
    } finally {
      recordMarkMetrics(timerId, workConfiguration, violationCounter, candidateCounter)
      postMark.invoke()
    }
  }

  private fun shouldExclude(resource: T, workConfiguration: WorkConfiguration): Boolean =
    resource.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions).also {
      if (it) {
        log.info("Excluding resource {} out of {}", resource, resourcesExcludedCounter.incrementAndGet())
      }
    }

  /**
   * deletes violating resources
   */
  override fun clean(markedResource: MarkedResource, workConfiguration: WorkConfiguration, postClean: () -> Unit) {
    try {
      getUpstreamResource(markedResource, workConfiguration)
        .let { resource ->
          if (resource == null) {
            log.info("Resource {} no longer exists", markedResource)
            resourceTrackingRepository.remove(markedResource)
          } else {
            resource
              .takeIf { !it.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions) }
              ?.let { upstreamResource ->
                rules.mapNotNull {
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
        }
    } catch (e: Exception) {
      recordFailureForAction(Action.DELETE, workConfiguration, e)
    } finally {
      postClean.invoke()
    }
  }

  private val markDurationTimer: LongTaskTimer = registry.longTaskTimer("swabbie.resources.mark.duration")
  private val markViolationsId: Id = registry.createId("swabbie.resources.markViolations")
  private val resourcesVisitedId: Id = registry.createId("swabbie.resources.visited")
  private val resourcesExcludedId: Id = registry.createId("swabbie.resources.excluded")
  private val resourceFailureId: Id = registry.createId("swabbie.resources.failures")
  private val candidatesCountId: Id = registry.createId("swabbie.resources.candidatesCount")
  private fun recordMarkMetrics(markerTimerId: Long,
                                workConfiguration: WorkConfiguration,
                                violationCounter: AtomicInteger,
                                candidateCounter: AtomicInteger) {
    markDurationTimer.stop(markerTimerId)
    registry.gauge(
      candidatesCountId.withTags(
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName
      )).set(candidateCounter.toDouble())

    registry.gauge(
      markViolationsId.withTags(
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName
      )).set(violationCounter.toDouble())

    registry.gauge(
      resourcesExcludedId.withTags(
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName
      )).set(resourcesExcludedCounter.toDouble())
  }

  private fun recordFailureForAction(action: Action, workConfiguration: WorkConfiguration, e: Exception) {
    log.error("Failed while invoking $javaClass", e)
    registry.counter(
      resourceFailureId.withTags(
        "action", action.name,
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName,
        "exception", e.javaClass.simpleName
      )).increment()
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this

  abstract fun remove(markedResource: MarkedResource, workConfiguration: WorkConfiguration)
}
