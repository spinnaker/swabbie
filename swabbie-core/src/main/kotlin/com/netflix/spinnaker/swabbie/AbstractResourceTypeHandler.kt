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

import com.google.common.collect.Lists
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.lock.LockManager.LockOptions
import com.netflix.spinnaker.swabbie.events.*
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.shouldExclude
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ReceiveChannel
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
  private val notifiers: List<Notifier>,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val lockingService: Optional<LockingService>,
  private val retrySupport: RetrySupport
) : ResourceTypeHandler<T>, MetricsSupport(registry) {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  private val resourceOwnerField: String = "swabbieResourceOwner"

  /**
   * deletes a marked resource. Each handler must implement this function.
   * Asynchronously produces resources that were successfully deleted for additional processing
   */
  abstract fun deleteResources(
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ): ReceiveChannel<MarkedResource>

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
      Action.MARK -> withLocking(action, workConfiguration) {
        doMark(workConfiguration, callback)
      }

      Action.DELETE -> withLocking(action, workConfiguration) {
        doDelete(workConfiguration, callback)
      }

      Action.NOTIFY -> withLocking(action, workConfiguration) {
        doNotify(workConfiguration, callback)
      }

      else -> log.warn("Invalid action {}", action.name)
    }
  }

  private fun withLocking(
    action: Action,
    workConfiguration: WorkConfiguration,
    callback: () -> Unit
  ) {
    if (lockingService.isPresent) {
      val normalizedLockName = ("${action.name}." + workConfiguration.namespace)
        .replace(":", ".")
        .toLowerCase()
      val lockOptions = LockOptions()
        .withLockName(normalizedLockName)
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
    exclusionCounters[Action.MARK] = AtomicInteger(0)
    val timerId = markDurationTimer.start()
    val candidateCounter = AtomicInteger(0)
    val violationCounter = AtomicInteger(0)
    val totalResourcesVisitedCounter = AtomicInteger(0)
    val markedResourceIds = mutableMapOf<String, List<Summary>>()
    try {
      log.info("${javaClass.simpleName} running. Configuration: ", workConfiguration)
      val candidates: List<T>? = getCandidates(workConfiguration)
      if (candidates == null || candidates.isEmpty()) {
        return
      }

      log.info("fetched {} resources. Configuration: {}", candidates.size, workConfiguration)
      totalResourcesVisitedCounter.set(candidates.size)
      candidates.let {
        resolveOwners(workConfiguration, it)
      }

      candidates.filterOutExcludedCandidates(workConfiguration).let { filteredCandidates ->
        val maxItemsToProcess = Math.min(filteredCandidates.size, workConfiguration.maxItemsProcessedPerCycle)

        // list of currently marked & stored resources
        val markedCandidates: List<MarkedResource> = Optional.ofNullable(resourceRepository.getMarkedResources())
          .orElse(emptyList())
          .filter {
            it.namespace == workConfiguration.namespace
          }

        for (candidate in filteredCandidates) {
          if (candidateCounter.get() > maxItemsToProcess) {
            log.info("Max items to process reached {}...", maxItemsToProcess)
            break
          }

          try {
            val violations: List<Summary> = candidate.getViolations()
            markedCandidates.find {
              it.resourceId == candidate.resourceId
            }.let { alreadyMarkedCandidate ->
              when {
                violations.isEmpty() -> ensureResourceUnmarked(alreadyMarkedCandidate, workConfiguration)
                !violations.isEmpty() && alreadyMarkedCandidate == null -> {
                  val newMarkedResource = MarkedResource(
                    resource = candidate,
                    summaries = violations,
                    namespace = workConfiguration.namespace,
                    resourceOwner = candidate.details[resourceOwnerField] as String,
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
          } catch (e: Exception) {
            log.error("Failed while invoking ${javaClass.simpleName}", e)
            recordFailureForAction(Action.MARK, workConfiguration, e)
          }
        }
      }
      printResult(candidateCounter, totalResourcesVisitedCounter, workConfiguration, markedResourceIds, Action.MARK)
    } finally {
      recordMarkMetrics(timerId, workConfiguration, violationCounter, candidateCounter)
      postMark.invoke()
    }
  }

  private fun List<T>.filterOutExcludedCandidates(workConfiguration: WorkConfiguration): List<T> {
    return this.filter {
      !shouldExcludeResource(it, workConfiguration, Action.MARK)
    }
  }

  private fun resolveOwners(
    workConfiguration: WorkConfiguration,
    candidates: List<T>
  ) = runBlocking<Unit> {
    candidates.forEach { candidate ->
      launch {
        candidate.set(
          resourceOwnerField,
          ownerResolver.resolve(candidate) ?: workConfiguration.notificationConfiguration.defaultDestination
        )
      }
    }
  }

  private fun ensureResourceUnmarked(
    markedResource: MarkedResource?,
    workConfiguration: WorkConfiguration
  ) {
    if (markedResource != null && !workConfiguration.dryRun) {
      try {
        log.info("{} is no longer a candidate.", markedResource)
        resourceRepository.remove(markedResource)
        applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
      } catch (e: Exception) {
        log.error("Failed to unmark resource {}", markedResource, e)
      }
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
    log.info("** ${action.name} Summary: {} CANDIDATES OUT OF {} SCANNED. EXCLUDED {}. CONFIGURATION: {}**",
      candidateCounter.get(),
      totalResourcesVisitedCounter.get(),
      exclusionCounters[action],
      workConfiguration
    )

    if (candidateCounter.get() > 0) {
      log.debug("** ${action.name} Result. Configuration: {} **", workConfiguration)
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
    if ((action != null && action != Action.NOTIFY) &&
      DAYS.between(creationDate, LocalDate.now()) < workConfiguration.maxAge) {
      log.debug("Excluding {} newer than {} days", resource, workConfiguration.maxAge)
      return true
    }

    return shouldExclude(resource, workConfiguration, exclusionPolicies, log).also { excluded ->
      if (excluded) {
        exclusionCounters[action]?.incrementAndGet()
      }
    }
  }

  private fun doDelete(workConfiguration: WorkConfiguration, postClean: () -> Unit) {
    exclusionCounters[Action.DELETE] = AtomicInteger(0)
    val candidateCounter = AtomicInteger(0)
    val markedResourceIds = mutableMapOf<String, List<Summary>>()
    try {
      resourceRepository.getMarkedResourcesToDelete()?.filter {
        it.notificationInfo != null && !workConfiguration.dryRun
      }?.sortedBy {
        it.resource.createTs
      }.let { toDelete ->
        if (toDelete == null || toDelete.isEmpty()) {
          log.info("Nothing to delete. Configuration: {}", workConfiguration)
          return
        }

        val postDeleteProcessingJobs = mutableListOf<Job>()
        toDelete.filter { r ->
          val shouldSkip = try {
            getCandidate(r, workConfiguration).let {
              it == null || shouldExcludeResource(it, workConfiguration, Action.DELETE) || it.getViolations().isEmpty()
            }
          } catch (e: Exception) {
            log.error("Failed to inspect ${r.resourceId} for deletion. Configuration: {}", workConfiguration, e)
            true
          }

          if (shouldSkip) {
            ensureResourceUnmarked(r, workConfiguration)
          }

          !shouldSkip
        }.let { filteredCandidateList ->
          val maxItemsToProcess = Math.min(filteredCandidateList.size, workConfiguration.maxItemsProcessedPerCycle)

          filteredCandidateList.subList(0, maxItemsToProcess).let {
            Lists.partition(it, workConfiguration.itemsProcessedBatchSize).forEach { partition ->
              try {
                val deleteChannel: ReceiveChannel<MarkedResource> = deleteResources(partition, workConfiguration)
                postDeleteProcessingJobs.add(
                  postDeleteProcessor(workConfiguration, deleteChannel, candidateCounter, markedResourceIds)
                )
              } catch (e: Exception) {
                log.error("Failed to delete ${it}. Configuration: {}", workConfiguration, e)
                recordFailureForAction(Action.DELETE, workConfiguration, e)
              }
            }
          }
        }

        runBlocking {
          postDeleteProcessingJobs.joinAll()
        }

        printResult(candidateCounter, AtomicInteger(toDelete.size), workConfiguration, markedResourceIds, Action.DELETE)
      }
    } finally {
      postClean.invoke()
    }
  }

  private fun postDeleteProcessor(
    workConfiguration: WorkConfiguration,
    channel: ReceiveChannel<MarkedResource>,
    candidateCounter: AtomicInteger,
    markedResourceIds: MutableMap<String, List<Summary>>
  ): Job = async {
    for (msg in channel) {
      log.info("Deleted {}. Configuration: {}", msg, workConfiguration)
      applicationEventPublisher.publishEvent(DeleteResourceEvent(msg, workConfiguration))
      resourceRepository.remove(msg)
      candidateCounter.incrementAndGet()
      markedResourceIds[msg.resourceId] = msg.summaries
    }
  }

  private fun T.getViolations(): List<Summary> {
    return rules.mapNotNull {
      it.apply(this).summary
    }
  }

  private fun doNotify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    exclusionCounters[Action.NOTIFY] = AtomicInteger(0)
    val candidateCounter = AtomicInteger(0)
    try {
      if (workConfiguration.dryRun || !workConfiguration.notificationConfiguration.enabled) {
        log.info("Notification not enabled for {}. Skipping...", workConfiguration)
        return
      }

      val markedResources = resourceRepository.getMarkedResources()
      if (markedResources == null || markedResources.isEmpty()) {
        log.info("Nothing to notify. Skipping...", workConfiguration)
        return
      }

      val markedResourceIds = mutableMapOf<String, List<Summary>>()
      val maxItemsToProcess = Math.min(markedResources.size, workConfiguration.maxItemsProcessedPerCycle)
      markedResources.subList(0, maxItemsToProcess)
        .filter {
          workConfiguration.namespace == it.namespace &&
            !shouldExcludeResource(it.resource as T, workConfiguration, Action.NOTIFY) && it.notificationInfo == null
        }.groupBy {
          it.resourceOwner
        }.forEach { ownersAndResources ->
          Lists.partition(
            ownersAndResources.value,
            workConfiguration.notificationConfiguration.itemsPerMessage
          ).forEach { partition ->
            try {
              sendNotification(ownersAndResources.key, workConfiguration, partition)
              partition.forEach { resource ->
                val offsetStampSinceMarked = ChronoUnit.MILLIS
                  .between(Instant.ofEpochMilli(resource.markTs!!), Instant.now(clock))

                resource
                  .apply {
                    projectedDeletionStamp += offsetStampSinceMarked
                    notificationInfo = NotificationInfo(
                      recipient = ownersAndResources.key,
                      notificationStamp = Instant.now(clock).toEpochMilli(),
                      notificationType = Notifier.NotificationType.EMAIL.name
                    )
                  }.also {
                    resourceRepository.upsert(it)
                    applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, workConfiguration))
                    candidateCounter.addAndGet(partition.size)
                  }

                markedResourceIds[resource.resourceId] = resource.summaries
              }
              log.debug("Notification sent to {} for {} resources", ownersAndResources.key, partition.size)
            } catch (e: Exception) {
              recordFailureForAction(Action.NOTIFY, workConfiguration, e)
            }
          }
        }

      printResult(
        candidateCounter,
        AtomicInteger(markedResources.size),
        workConfiguration,
        markedResourceIds,
        Action.NOTIFY
      )
    } finally {
      postNotify.invoke()
    }
  }

  private fun sendNotification(
    owner: String,
    workConfiguration:
    WorkConfiguration,
    resources: List<MarkedResource>
  ) {
    notifiers.forEach { notifier ->
      workConfiguration.notificationConfiguration.types.forEach { notificationType ->
        if (notificationType.equals(Notifier.NotificationType.EMAIL.name, true)) {
          val notificationContext = mapOf(
            "resourceOwner" to owner,
            "resources" to resources,
            "configuration" to workConfiguration,
            "resourceType" to workConfiguration.resourceType.formatted(),
            "spinnakerLink" to workConfiguration.notificationConfiguration.resourceUrl,
            "optOutLink" to workConfiguration.notificationConfiguration.optOutBaseUrl
          )

          retrySupport.retry({
            notifier.notify(
              recipient = owner,
              messageType = notificationType,
              additionalContext = notificationContext
            )
          }, maxAttempts, timeoutMillis, true)
        }
      }
    }
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this
}

const val timeoutMillis: Long = 5000
const val maxAttempts: Int = 3
