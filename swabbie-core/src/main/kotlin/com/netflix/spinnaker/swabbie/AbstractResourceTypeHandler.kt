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

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.lock.LockManager.LockOptions
import com.netflix.spinnaker.swabbie.echo.EchoService
import com.netflix.spinnaker.swabbie.echo.Notifier
import com.netflix.spinnaker.swabbie.events.*
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.shouldExclude
import com.netflix.spinnaker.swabbie.model.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.DAYS
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractResourceTypeHandler<out T : Resource>(
  registry: Registry,
  val clock: Clock,
  private val rules: List<Rule<T>>,
  private val resourceRepository: ResourceTrackingRepository,
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val ownerResolver: OwnerResolver<T>,
  private val notifier: Notifier,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val lockingService: Optional<LockingService>,
  private val retrySupport: RetrySupport
) : ResourceTypeHandler<T>, MetricsSupport(registry) {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  private val resourceOwnerField: String = "swabbieResourceOwner"

  /**
   * deletes a marked resource. Each handler must implement this function.
   */
  abstract fun deleteMarkedResource(markedResource: MarkedResource, workConfiguration: WorkConfiguration)

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    execute(workConfiguration, postMark, Action.MARK)
  }

  /**
   * deletes violating resources
   */
  override fun delete(workConfiguration: WorkConfiguration, postDelete: () -> Unit) {
    execute(workConfiguration, postDelete, Action.DELETE)
  }

  /**
   * Notifies resource owners
   */
  override fun notify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    execute(workConfiguration, postNotify, Action.NOTIFY)
  }

  /**
   * Dispatches work based on passed action
   */
  private fun execute(workConfiguration: WorkConfiguration, callback: () -> Unit, action: Action) {
    when (action) {
      Action.MARK -> withLocking(workConfiguration) {
        doMark(workConfiguration, callback)
      }

      Action.DELETE -> withLocking(workConfiguration) {
        doDelete(workConfiguration, callback)
      }

      Action.NOTIFY -> withLocking(workConfiguration) {
        doNotify(workConfiguration, callback)
      }

      else -> log.warn("Invalid action {}", action.name)
    }
  }

  private fun withLocking(workConfiguration: WorkConfiguration,
                          callback: () -> Unit
  ) {
    if (lockingService.isPresent) {
      val normalizedName = workConfiguration.namespace
        .replace(":", ".")

      val lockOptions = LockOptions()
        .withLockName(normalizedName.toLowerCase())
        .withMaximumLockDuration(lockingService.get().swabbieMaxLockDuration)
      lockingService.get().acquireLock(lockOptions, {
        callback.invoke()
      })
    } else {
      log.warn("***Locking not ENABLED***")
      callback.invoke()
    }
  }

  private fun doMark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    // initialize counters
    excludedResourcesDuringMarkCounter.set(0)
    val timerId = markDurationTimer.start()
    val candidateCounter = AtomicInteger(0)
    val violationCounter = AtomicInteger(0)
    val totalResourcesVisitedCounter = AtomicInteger(0)
    val markedResourceIds = mutableMapOf<String, List<Summary>>()
    try {
      log.info("${javaClass.simpleName} running. Configuration: ", workConfiguration)
      val candidates: List<T>? = getCandidates(workConfiguration)
      log.info("fetched {} resources. Configuration: {}", candidates?.size, workConfiguration)
      if (candidates == null || candidates.isEmpty()) {
        return
      }

      totalResourcesVisitedCounter.set(candidates.size)
      candidates.forEach { candidate ->
        candidate.set(resourceOwnerField, ownerResolver.resolve(candidate))
      }

      val markedCandidates: List<MarkedResource> = Optional.ofNullable(resourceRepository.getMarkedResources())
        .orElse(emptyList())
        .filter {
          it.namespace == workConfiguration.namespace
        }

      // filter out resources that should be excluded
      candidates.filter {
        !shouldExcludeResource(it, workConfiguration, Action.MARK)
      }.forEach { candidate ->
        try {
          getViolations(candidate).let { violations ->
            markedCandidates.find {
              it.resourceId == candidate.resourceId
            }.let { alreadyMarkedCandidate ->
              when {
                alreadyMarkedCandidate != null && violations.isEmpty() -> ensureResourceUnmarked(
                  alreadyMarkedCandidate,
                  workConfiguration
                )
                alreadyMarkedCandidate == null && !violations.isEmpty() -> {
                  val newMarkedResource = MarkedResource(
                    resource = candidate,
                    summaries = violations,
                    namespace = workConfiguration.namespace,
                    resourceOwner = candidate.details[resourceOwnerField] as? String,
                    projectedDeletionStamp = deletionTimestamp(workConfiguration)
                  )

                  if (!workConfiguration.dryRun) {
                    resourceRepository.upsert(newMarkedResource)
                    applicationEventPublisher.publishEvent(MarkResourceEvent(newMarkedResource, workConfiguration))
                    log.info("Marked resource {} for deletion", newMarkedResource)
                  }

                  candidateCounter.incrementAndGet()
                  violationCounter.addAndGet(violations.size)
                  markedResourceIds[newMarkedResource.resourceId] = violations
                }
              }
            }
          }
        } catch (e: Exception) {
          log.error("Failed while invoking ${javaClass.simpleName}", e)
          recordFailureForAction(Action.MARK, workConfiguration, e)
        }
      }

      printResult(candidateCounter, totalResourcesVisitedCounter, workConfiguration, markedResourceIds, Action.MARK)
    } finally {
      recordMarkMetrics(timerId, workConfiguration, violationCounter, candidateCounter)
      postMark.invoke()
    }
  }

  private fun ensureResourceUnmarked(
    markedResource: MarkedResource,
    workConfiguration: WorkConfiguration
  ) {
    if (!workConfiguration.dryRun) {
      log.info("{} is no longer a candidate.", markedResource)
      resourceRepository.remove(markedResource)
      applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
    }
  }

  private fun deletionTimestamp(workConfiguration: WorkConfiguration): Long =
    (workConfiguration.retention + 1).days.fromNow.atStartOfDay(clock.zone).toInstant().toEpochMilli()

  private fun printResult(
    candidateCounter: AtomicInteger,
    totalResourcesVisitedCounter: AtomicInteger,
    workConfiguration: WorkConfiguration,
    markedResourceIds: MutableMap<String, List<Summary>>,
    action: Action
  ) {
    log.info("** ${action.name} Summary: {} CLEANUP CANDIDATES OUT OF {} SCANNED. EXCLUDED {}. CONFIGURATION: {}**",
      candidateCounter.get(),
      totalResourcesVisitedCounter.get(),
      excludedResourcesDuringMarkCounter.get(),
      workConfiguration
    )

    if (candidateCounter.get() > 0) {
      log.debug("** ${action.name} list Configuration: {} **", workConfiguration)
      markedResourceIds.forEach {
        log.debug("$it")
      }
    }
  }

  private fun shouldExcludeResource(
    resource: T,
    workConfiguration: WorkConfiguration,
    action: Action? = null
  ): Boolean {
    val creationDate = Instant.ofEpochMilli(resource.createTs).atZone(ZoneId.systemDefault()).toLocalDate()
    if (DAYS.between(creationDate, LocalDate.now()) < workConfiguration.maxAge) {
      log.debug("Excluding {} newer than {} days", resource, workConfiguration.maxAge)
      return true
    }

    return shouldExclude(resource, workConfiguration, exclusionPolicies, log).also { excluded ->
      if (excluded) {
        if (action == Action.MARK) {
          excludedResourcesDuringMarkCounter.incrementAndGet()
        } else if (action == Action.DELETE) {
          excludedResourcesDuringDeleteCounter.incrementAndGet()
        }
      }
    }
  }

  private fun doDelete(workConfiguration: WorkConfiguration, postClean: () -> Unit) {
    val candidateCounter = AtomicInteger(0)
    val markedResourceIds = mutableMapOf<String, List<Summary>>()
    try {
      val toDelete: List<MarkedResource>? = resourceRepository.getMarkedResourcesToDelete()
      if (toDelete == null || toDelete.isEmpty()) {
        log.info("Nothing to delete. Configuration: {}", workConfiguration)
        return
      }

      for (r in toDelete) {
        try {
          val candidate: T? = getCandidate(r, workConfiguration)
          if (candidate == null) {
            ensureResourceUnmarked(r, workConfiguration)
            continue
          }

          candidate.set(resourceOwnerField, ownerResolver.resolve(candidate))
          if (shouldExcludeResource(candidate, workConfiguration, Action.DELETE)) {
            ensureResourceUnmarked(r, workConfiguration)
            continue
          }

          getViolations(candidate).let { violations ->
            if (violations.isEmpty()) {
              ensureResourceUnmarked(r, workConfiguration)
            } else {
              if (r.notificationInfo != null && !workConfiguration.dryRun) {
                retrySupport.retry({
                  deleteMarkedResource(r, workConfiguration)
                }, 3, 5000, true)

                applicationEventPublisher.publishEvent(DeleteResourceEvent(r, workConfiguration))
                resourceRepository.remove(r)
                log.info("Deleted {}. Configuration: {}", candidate, workConfiguration)

                candidateCounter.incrementAndGet()
                markedResourceIds[r.resourceId] = violations
              }
            }
          }
        } catch (e: Exception) {
          log.error("Failed to inspect ${r.resourceId} for deletion. Configuration: {}", workConfiguration, e)
          recordFailureForAction(Action.DELETE, workConfiguration, e)
        }
      }

      printResult(candidateCounter, AtomicInteger(toDelete.size), workConfiguration, markedResourceIds, Action.DELETE)
    } finally {
      postClean.invoke()
    }
  }

  private fun getViolations(candidate: T): List<Summary> {
    return rules.mapNotNull {
      it.apply(candidate).summary
    }
  }

  private fun doNotify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    try {
      log.info("Notification Agent Started with configuration {}", workConfiguration)
      val owners = mutableMapOf<String, MutableList<MarkedResource>>()
      resourceRepository.getMarkedResources()
        ?.filter {
          workConfiguration.namespace.equals(it.namespace, ignoreCase = true) && it.notificationInfo == null
        }?.forEach { markedResource ->
          markedResource.resourceOwner?.let { owner ->
            if (owners[owner] == null) {
              owners[owner] = mutableListOf(markedResource)
            } else {
              owners[owner]!!.add(markedResource)
            }
          }
        }

      owners.let { ownersToResources ->
        ownersToResources.forEach { ownerToResources ->
          log.info("DryRun={}, shouldNotify={}, user={}, {} cleanup candidate(s)",
            workConfiguration.dryRun,
            workConfiguration.notificationConfiguration!!.notifyOwner,
            ownerToResources.key,
            ownerToResources.value.size
          )

          if (workConfiguration.dryRun || !workConfiguration.notificationConfiguration.notifyOwner) {
            log.info("Skipping notifications. Configuration: {}", workConfiguration)
          } else {
            ownerToResources.value.let { resources ->
              notifier.notify(
                recipient = ownerToResources.key,
                messageType = EchoService.Notification.Type.EMAIL.name,
                additionalContext = mapOf(
                  "resourceOwner" to ownerToResources.key,
                  "resources" to resources,
                  "configuration" to workConfiguration,
                  "resourceType" to workConfiguration.resourceType.formatted(),
                  "spinnakerLink" to workConfiguration.notificationConfiguration.spinnakerResourceUrl,
                  "optOutLink" to workConfiguration.notificationConfiguration.optOutUrl
                )
              )

              log.debug("Notification sent to {} for {}", ownerToResources.key, resources)
              resources.forEach { resource ->
                val offsetStampSinceMarked: Long = ChronoUnit.MILLIS.between(
                  Instant.ofEpochMilli(resource.createdTs!!),
                  Instant.now(clock)
                )

                resource.apply {
                  projectedDeletionStamp += offsetStampSinceMarked
                  notificationInfo = NotificationInfo(
                    recipient = ownerToResources.key,
                    notificationStamp = Instant.now(clock).toEpochMilli(),
                    notificationType = EchoService.Notification.Type.EMAIL.name
                  )
                }.also {
                  resourceRepository.upsert(it)
                  applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, workConfiguration))
                }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      recordFailureForAction(Action.NOTIFY, workConfiguration, e)
    } finally {
      postNotify.invoke()
    }
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this
}
