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
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.events.*
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.shouldExclude
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.DAYS
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractResourceTypeHandler<T : Resource>(
  registry: Registry,
  val clock: Clock,
  private val rules: List<Rule<T>>,
  private val resourceRepository: ResourceTrackingRepository,
  private val resourceStateRepository: ResourceStateRepository,
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
    val markedResources = mutableListOf<MarkedResource>()
    try {
      log.info("${javaClass.simpleName} running. Configuration: ", workConfiguration)
      val candidates: List<T>? = getCandidates(workConfiguration)
      if (candidates == null || candidates.isEmpty()) {
        return
      }
      log.info("fetched {} resources. Configuration: {}", candidates.size, workConfiguration)
      totalResourcesVisitedCounter.set(candidates.size)

      val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
        .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

      val filteredCandidatesWithOwners = candidates
        .withResolvedOwners(workConfiguration)
        .filter { !shouldExcludeResource(it, workConfiguration, optedOutResourceStates, Action.MARK) }

      preProcessCandidates(filteredCandidatesWithOwners, workConfiguration)
        .let { filteredCandidates ->
          val maxItemsToProcess = Math.min(filteredCandidates.size, workConfiguration.maxItemsProcessedPerCycle)

          // list of currently marked & stored resources
          val markedCandidates: List<MarkedResource> = resourceRepository.getMarkedResources()
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
                  alreadyMarkedCandidate == null -> {
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
                    markedResources.add(newMarkedResource)
                  }
                  else -> {
                    // already marked, skipping.
                    log.debug("Already marked resource " + alreadyMarkedCandidate.resource.resourceId + " ...skipping")
                  }
                }
              }
            } catch (e: Exception) {
              log.error("Failed while invoking ${javaClass.simpleName}", e)
              recordFailureForAction(Action.MARK, workConfiguration, e)
            }
          }
        }

      printResult(candidateCounter, totalResourcesVisitedCounter, workConfiguration, markedResources, Action.MARK)
    } finally {
      recordMarkMetrics(timerId, workConfiguration, violationCounter, candidateCounter)
      postMark.invoke()
    }
  }

  override fun evaluateCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): ResourceEvauation {
    val candidate = getCandidate(resourceId, resourceName, workConfiguration) ?: return ResourceEvauation(
      workConfiguration.namespace,
      resourceId,
      false,
      "Resource does not exist in the given namespace",
      emptyList()
    )

    val resourceState = resourceStateRepository.get(resourceId, workConfiguration.namespace)
    if (resourceState != null && resourceState.optedOut) {
      return ResourceEvauation(
        workConfiguration.namespace,
        resourceId,
        false,
        "Resource has been opted out",
        emptyList()
      )
    }

    val candidates = listOf(candidate)
      .withResolvedOwners(workConfiguration)
      .filter { !shouldExcludeResource(it, workConfiguration, emptyList(), Action.MARK) }

    if (candidates.isEmpty()) {
      return ResourceEvauation(
        workConfiguration.namespace,
        resourceId,
        false,
        "Resource has been excluded from marking",
        emptyList()
      )
    }

    val preprocessedCandidate = preProcessCandidates(candidates, workConfiguration).first()
    val wouldMark = preprocessedCandidate.getViolations().isNotEmpty()
    return ResourceEvauation(
      workConfiguration.namespace,
      resourceId,
      wouldMark,
      if (wouldMark) "Resource has violations" else "Resource does not have violations",
      preprocessedCandidate.getViolations()
    )
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
    markedResources: List<MarkedResource>,
    action: Action
  ) {
    val totalProcessed = Math.min(workConfiguration.maxItemsProcessedPerCycle, totalResourcesVisitedCounter.get())
    log.info("** ${action.name} Summary: {} CANDIDATES OUT OF {} PROCESSED. {} SCANNED. EXCLUDED {}. CONFIGURATION: {}**",
      candidateCounter.get(),
      totalProcessed,
      totalResourcesVisitedCounter.get(),
      exclusionCounters[action],
      workConfiguration
    )

    if (candidateCounter.get() > 0) {
      log.debug("** ${action.name} Result. Configuration: {} **", workConfiguration)
      markedResources.forEach {
        log.debug(it.toString())
      }
    }
  }

  private fun shouldExcludeResource(
    resource: T,
    workConfiguration: WorkConfiguration,
    optedOutResourceStates: List<ResourceState>,
    action: Action
  ): Boolean {
    val creationDate = Instant.ofEpochMilli(resource.createTs).atZone(ZoneId.systemDefault()).toLocalDate()
    if (action != Action.NOTIFY && DAYS.between(creationDate, LocalDate.now()) < workConfiguration.maxAge) {
      log.debug("Excluding {} newer than {} days", resource, workConfiguration.maxAge)
      return true
    }

    optedOutResourceStates.find { it.markedResource.resourceId == resource.resourceId }?.let {
      // TODO: opting out should expire based on configured time
      log.debug("Skipping Opted out resource {}", resource)
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
    val markedResources = mutableListOf<MarkedResource>()
    try {
      resourceRepository.getMarkedResourcesToDelete().filter {
        it.notificationInfo != null && !workConfiguration.dryRun
      }.sortedBy {
        it.resource.createTs
      }.let { toDelete ->
        if (toDelete.isEmpty()) {
          log.info("Nothing to delete. Configuration: {}", workConfiguration)
          return
        }

        val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
          .filter {
            it.markedResource.namespace == workConfiguration.namespace && it.optedOut
          }

        var candidates: List<T>? = getCandidates(workConfiguration)
        if (candidates == null) {
          toDelete.forEach {
            ensureResourceUnmarked(it, workConfiguration)
          }

          log.info("Nothing to delete. No upstream resources, Configuration: {}", workConfiguration)
          return
        }

        candidates = candidates
          .filter { candidate ->
            toDelete.any { candidate.resourceId == it.resourceId }
          }.withResolvedOwners(workConfiguration).also {
            preProcessCandidates(it, workConfiguration)
          }

        toDelete.filter { r ->
          var shouldSkip = false
          val candidate = candidates.find { it.resourceId == r.resourceId }
          if ((candidate == null || candidate.getViolations().isEmpty()) ||
            shouldExcludeResource(candidate, workConfiguration, optedOutResourceStates, Action.DELETE)) {
            shouldSkip = true
            ensureResourceUnmarked(r, workConfiguration)
          }
          !shouldSkip
        }.let { filteredCandidateList ->
          val maxItemsToProcess = Math.min(filteredCandidateList.size, workConfiguration.maxItemsProcessedPerCycle)
          filteredCandidateList.subList(0, maxItemsToProcess).let {
            partitionList(it, workConfiguration).forEach { partition ->
              if (!partition.isEmpty()) {
                try {
                  val deleteChannel: ReceiveChannel<MarkedResource> = deleteResources(partition, workConfiguration)
                  runBlocking {
                    deleteChannel.consumeEach { successFullyDeletedResource ->
                      log.info("Deleted {}. Configuration: {}", successFullyDeletedResource, workConfiguration)
                      resourceRepository.remove(successFullyDeletedResource)
                      candidateCounter.incrementAndGet()
                      markedResources.add(successFullyDeletedResource)
                      applicationEventPublisher.publishEvent(DeleteResourceEvent(successFullyDeletedResource, workConfiguration))
                    }
                  }
                } catch (e: Exception) {
                  log.error("Failed to delete $it. Configuration: {}", workConfiguration, e)
                  recordFailureForAction(Action.DELETE, workConfiguration, e)
                }
              }
            }
          }
        }

        printResult(candidateCounter, AtomicInteger(toDelete.size), workConfiguration, markedResources, Action.DELETE)
      }
    } finally {
      postClean.invoke()
    }
  }

  /**
   * Partitions the list of marked resources to process:
   * For convenience, partitions are grouped by relevance (here the derived app they are associated with)
   */
  fun partitionList(
    markedResources: List<MarkedResource>,
    configuration: WorkConfiguration
  ): List<List<MarkedResource>> {
    val partitions = mutableListOf<List<MarkedResource>>()
    markedResources.groupBy {
      FriggaReflectiveNamer().deriveMoniker(it.resource).app
    }.map {
      Lists.partition(it.value, configuration.itemsProcessedBatchSize).forEach {
        partitions.add(it)
      }
    }

    return partitions.sortedByDescending { it.size }
  }

  private fun T.getViolations(): List<Summary> {
    return rules.mapNotNull {
      it.apply(this).summary
    }
  }

  private fun List<T>.withResolvedOwners(workConfiguration: WorkConfiguration): List<T> =
    this.map { candidate ->
      candidate.apply {
        set(
          resourceOwnerField,
          ownerResolver.resolve(candidate) ?: workConfiguration.notificationConfiguration.defaultDestination
        )
      }
    }

  private fun doNotify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    exclusionCounters[Action.NOTIFY] = AtomicInteger(0)
    val candidateCounter = AtomicInteger(0)
    // used to print out result of resources for which notifications were sent
    val notifiedMarkedResource: MutableList<MarkedResource> = mutableListOf()
    try {
      if (workConfiguration.dryRun || !workConfiguration.notificationConfiguration.enabled) {
        log.info("Notification not enabled for {}. Skipping...", workConfiguration)
        return
      }

      val markedResources = resourceRepository.getMarkedResources()
        .filter {
          it.notificationInfo == null && workConfiguration.namespace == it.namespace
        }

      val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
        .filter {
          it.markedResource.namespace == workConfiguration.namespace && it.optedOut
        }

      if (markedResources.isEmpty()) {
        log.info("Nothing to notify. Skipping...", workConfiguration)
        return
      }

      val maxItemsToProcess = Math.min(markedResources.size, workConfiguration.maxItemsProcessedPerCycle)
      markedResources.subList(0, maxItemsToProcess)
        .filter {
          !shouldExcludeResource(it.resource as T, workConfiguration, optedOutResourceStates, Action.NOTIFY)
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
                    candidateCounter.incrementAndGet()
                  }

                notifiedMarkedResource.add(resource)
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
        notifiedMarkedResource,
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
