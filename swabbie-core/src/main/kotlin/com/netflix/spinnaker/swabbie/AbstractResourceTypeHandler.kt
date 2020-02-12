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
import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.OptOutResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.shouldExclude
import com.netflix.spinnaker.swabbie.model.AlwaysCleanRule
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.OnDemandMarkData
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.ResourceEvaluation
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.NotificationTask
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.rules.RulesEngine
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

abstract class AbstractResourceTypeHandler<T : Resource>(
  private val registry: Registry,
  val clock: Clock,
  private val rulesEngine: RulesEngine,
  private val resourceRepository: ResourceTrackingRepository,
  private val resourceStateRepository: ResourceStateRepository,
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val ownerResolver: OwnerResolver<T>,
  private val notifier: Notifier,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
  private val swabbieProperties: SwabbieProperties,
  private val dynamicConfigService: DynamicConfigService,
  private val notificationQueue: NotificationQueue
) : ResourceTypeHandler<T> {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  private val resourceOwnerField: String = "swabbieResourceOwner"
  private val resourcesVisitedId: Id = registry.createId("swabbie.resources.visited")
  private val resourcesExcludedId: Id = registry.createId("swabbie.resources.excluded")
  private val resourceFailureId: Id = registry.createId("swabbie.resources.failures")

  /**
   * deletes a marked resource. Each handler must implement this function.
   * Deleted resources are produced via [DeleteResourceEvent]
   * for additional processing
   */
  abstract fun deleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration)

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration) {
    doMark(workConfiguration)
  }

  /**
   * deletes violating resources
   */
  override fun delete(workConfiguration: WorkConfiguration) {
    doDelete(workConfiguration)
  }

  /**
   * Notifies for everything in resourceRepository.getMarkedResources() that doesn't have notificationInfo
   */
  override fun notify(workConfiguration: WorkConfiguration) {
    if (workConfiguration.dryRun) {
      log.info("Notification not enabled in dryRun for {}. Skipping...", workConfiguration.toLog())
      return
    }

    val markedResources = workConfiguration.getMarkedResourcesToNotify()
    if (markedResources.isEmpty()) {
      log.info("Nothing to notify for {}. Skipping...", workConfiguration)
      return
    }

    log.info("Processing ${markedResources.size} for notification in ${javaClass.simpleName}")
    if (workConfiguration.notificationConfiguration.enabled) {
      // Notifications are routinely batched and sent by [com.netflix.spinnaker.swabbie.notifications.NotificationSender]
      notificationQueue.add(workConfiguration)
    } else {
      // Do not send notifications for these resources. They are updated with a new deletion timestamp to offset
      // the time elapsed since they were marked.
      markedResources
        .filterExcludedAndOptedOut(workConfiguration)
        .forEach { markedResource ->
          markedResource
            .withNotificationInfo()
            .withAdditionalTimeForDeletion(
              Instant.ofEpochMilli(markedResource.markTs!!).until(Instant.now(clock), ChronoUnit.MILLIS))
            .also {
              resourceRepository.upsert(markedResource)
            }
        }
    }
  }

  /**
   * Adds a new notification task to the [NotificationQueue]
   * @see [com.netflix.spinnaker.swabbie.notifications.NotificationSender]
   */
  private fun NotificationQueue.add(workConfiguration: WorkConfiguration) {
    add(NotificationTask(
      namespace = workConfiguration.namespace,
      resourceType = workConfiguration.resourceType
    ))
  }

  /**
   * This finds the resource, and then opts it out even if it was not marked.
   * This function is used when a caller makes an opt out request through the controller
   *  and the resource has not already been marked.
   */
  override fun optOut(resourceId: String, workConfiguration: WorkConfiguration): ResourceState {
    val resource = getCandidate(
      resourceId = resourceId,
      workConfiguration = workConfiguration
    ) ?: throw NotFoundException()

    val markedResource = resourceRepository.find(resourceId, workConfiguration.namespace)
      ?: MarkedResource(
          resource = resource,
          summaries = emptyList(),
          namespace = workConfiguration.namespace,
          projectedDeletionStamp = -1L
        )

    log.info("Opting out resource ${markedResource.resourceId} in namespace ${workConfiguration.namespace}")
    val status = Status(Action.OPTOUT.name, clock.instant().toEpochMilli())
    resourceRepository.remove(markedResource)
    applicationEventPublisher.publishEvent(OptOutResourceEvent(markedResource, workConfiguration))
    val optOutState = resourceStateRepository.get(resource.resourceId, workConfiguration.namespace)
      ?: ResourceState(
          markedResource = markedResource,
          deleted = false,
          statuses = mutableListOf(status)
        )

    return resourceStateRepository.upsert(optOutState.copy(optedOut = true, currentStatus = status))
  }

  override fun markResource(resourceId: String, onDemandMarkData: OnDemandMarkData, workConfiguration: WorkConfiguration) {
    val resource = getCandidate(resourceId, "", workConfiguration)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("Resource marked via the api", AlwaysCleanRule::class.java.simpleName)),
      namespace = workConfiguration.namespace,
      resourceOwner = onDemandMarkData.resourceOwner,
      projectedDeletionStamp = onDemandMarkData.projectedDeletionStamp,
      notificationInfo = onDemandMarkData.notificationInfo,
      lastSeenInfo = onDemandMarkData.lastSeenInfo
    )

    log.debug("Marking resource $resourceId in namespace ${workConfiguration.namespace} without checking rules.")
    resourceRepository.upsert(markedResource)
    applicationEventPublisher.publishEvent(MarkResourceEvent(markedResource, workConfiguration))
  }

  override fun deleteResource(resourceId: String, workConfiguration: WorkConfiguration) {
    val markedResource = resourceRepository.find(resourceId, workConfiguration.namespace)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    log.debug("Deleting resource $resourceId in namespace ${workConfiguration.namespace} without checking rules.")
    deleteResources(listOf(markedResource), workConfiguration)
  }

  private fun doMark(workConfiguration: WorkConfiguration) {
    val candidates: List<T>? = getCandidates(workConfiguration)
    if (candidates == null || candidates.isEmpty()) {
      return
    }

    log.debug("Fetched {} resources. Configuration: {}", candidates.size, workConfiguration.namespace)
    val markedCandidates: List<MarkedResource> = resourceRepository.getMarkedResources()
      .filter { it.namespace == workConfiguration.namespace }

    val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
      .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

    val preProcessedCandidates = candidates
      .shuffled()
      .withResolvedOwners(workConfiguration)
      .also { preProcessCandidates(it, workConfiguration) }
      .filter { !shouldExcludeResource(it, workConfiguration, optedOutResourceStates, Action.MARK) }

    val processedCount = 0
    val excludedCount = candidates.size - preProcessedCandidates.size
    val maxItemsToProcess = min(preProcessedCandidates.size, workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService))
    for (candidate in preProcessedCandidates) {
      if (processedCount.inc() > maxItemsToProcess) {
        log.info("Max items ({}) to process reached, short-circuiting", maxItemsToProcess)
        break
      }

      try {
        val violations: List<Summary> = getViolations(candidate, workConfiguration)
        val alreadyMarkedCandidate = markedCandidates.find { it.resourceId == candidate.resourceId }
        when {
          violations.isEmpty() -> {
            ensureResourceUnmarked(alreadyMarkedCandidate,
              workConfiguration,
              "Resource no longer qualifies for deletion")
          }
          alreadyMarkedCandidate == null -> {
            val newMarkedResource = MarkedResource(
              resource = candidate,
              summaries = violations,
              namespace = workConfiguration.namespace,
              resourceOwner = candidate.details[resourceOwnerField] as String,
              projectedDeletionStamp = deletionTimestamp(workConfiguration),
              lastSeenInfo = resourceUseTrackingRepository.getLastSeenInfo(candidate.resourceId)
            )

            if (!workConfiguration.dryRun) {
              // todo eb: should this upsert event happen in ResourceTrackingManager?
              resourceRepository.upsert(newMarkedResource)
              applicationEventPublisher.publishEvent(MarkResourceEvent(newMarkedResource, workConfiguration))
              log.info("Marked resource {} for deletion", newMarkedResource)
            }
          }
          else -> {
            // already marked, skipping.
            log.debug("Already marked resource " + alreadyMarkedCandidate.resourceId + " ...skipping")
          }
        }
      } catch (e: Exception) {
        log.error("Failed while invoking ${javaClass.simpleName}", e)
        recordFailureForAction(Action.MARK, workConfiguration, e)
      }
    }

    recordCandidateCount(workConfiguration, Action.MARK, candidates.size)
    recordExcludedResources(workConfiguration, Action.MARK, excludedCount)

    Action.MARK.printResult(workConfiguration, processed = maxItemsToProcess, excluded = excludedCount, total = candidates.size)
  }

  override fun recalculateDeletionTimestamp(namespace: String, retentionSeconds: Long, numResources: Int) {
    val newTimestamp = deletionTimestamp(retentionSeconds)
    log.info("Updating deletion time to $newTimestamp for $numResources resources in ${javaClass.simpleName}.")

    val markedResources = resourceRepository.getMarkedResources()
      .filter { it.namespace == namespace }
      .sortedBy { it.resource.createTs }

    var countUpdated = 0
    markedResources.forEach { resource ->
      if (resource.projectedDeletionStamp > newTimestamp && countUpdated < numResources) {
        resource.projectedDeletionStamp = newTimestamp
        resourceRepository.upsert(resource)
        countUpdated += 1
      }
    }

    log.info("Updating deletion time to $newTimestamp complete for $countUpdated resources")
  }

  override fun evaluateCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): ResourceEvaluation {
    val candidate = getCandidate(resourceId, resourceName, workConfiguration) ?: return ResourceEvaluation(
      workConfiguration.namespace,
      resourceId,
      false,
      "Resource does not exist in the given namespace",
      emptyList()
    )

    val resourceState = resourceStateRepository.get(resourceId, workConfiguration.namespace)
    if (resourceState != null && resourceState.optedOut) {
      return ResourceEvaluation(
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
      return ResourceEvaluation(
        workConfiguration.namespace,
        resourceId,
        false,
        "Resource has been excluded from marking",
        emptyList()
      )
    }

    val preprocessedCandidate = preProcessCandidates(candidates, workConfiguration).first()
    val wouldMark = getViolations(preprocessedCandidate, workConfiguration).isNotEmpty()
    return ResourceEvaluation(
      workConfiguration.namespace,
      resourceId,
      wouldMark,
      if (wouldMark) "Resource has violations" else "Resource does not have violations",
      getViolations(preprocessedCandidate, workConfiguration)
    )
  }

  private fun ensureResourceUnmarked(
    markedResource: MarkedResource?,
    workConfiguration: WorkConfiguration,
    reason: String
  ) {
    if (markedResource != null && !workConfiguration.dryRun) {
      try {
        log.debug("{} is no longer a candidate because: $reason.", markedResource.uniqueId())
        resourceRepository.remove(markedResource)
        applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
      } catch (e: Exception) {
        log.error("Failed to unmark resource {}", markedResource, e)
      }
    }
  }

  fun deletionTimestamp(workConfiguration: WorkConfiguration): Long {
    val daysInFuture = workConfiguration.retention.toLong()
    val seconds = TimeUnit.DAYS.toSeconds(daysInFuture)
    return deletionTimestamp(seconds)
  }

  private fun deletionTimestamp(retentionSeconds: Long): Long {
    val proposedTime = clock.instant().plusSeconds(retentionSeconds).toEpochMilli()
    return swabbieProperties.schedule.getNextTimeInWindow(proposedTime)
  }

  private fun Action.printResult(
    workConfiguration: WorkConfiguration,
    processed: Int,
    excluded: Int,
    total: Int
  ) {
    log.info("$name Summary: {} processed out of {}. {} excluded. Configuration: {}",
      processed,
      total,
      excluded,
      workConfiguration.namespace
    )
  }

  private fun shouldExcludeResource(
    resource: T,
    workConfiguration: WorkConfiguration,
    optedOutResourceStates: List<ResourceState>,
    action: Action
  ): Boolean {
    val hasAlwaysCleanRule = rulesEngine.getRules(workConfiguration)
        .any { rule ->
          rule.name() == AlwaysCleanRule::class.java.simpleName
        }

    if (hasAlwaysCleanRule || resource.expired(clock)) {
      return false
    }

    if (action != Action.NOTIFY && resource.age(clock).toDays() < workConfiguration.maxAge) {
      log.debug("Excluding resource (newer than {} days) {}", workConfiguration.maxAge, resource)
      return true
    }

    optedOutResourceStates.find { it.markedResource.resourceId == resource.resourceId }?.let {
      log.debug("Skipping Opted out resource {}", resource)
      return true
    }

    return shouldExclude(resource, workConfiguration, exclusionPolicies, log)
  }

  private fun doDelete(workConfiguration: WorkConfiguration) {
    if (workConfiguration.dryRun) {
      log.info("Deletion not enabled in dryRun for {}. Skipping...", workConfiguration.toLog())
      return
    }

    val currentMarkedResourcesToDelete = workConfiguration.getMarkedResourcesToDelete()
    if (currentMarkedResourcesToDelete.isEmpty()) {
      log.debug("Nothing to delete. Configuration: {}", workConfiguration.toLog())
      return
    }

    val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
      .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

    val candidates: List<T>? = getCandidates(workConfiguration)
    if (candidates == null) {
      currentMarkedResourcesToDelete
        .forEach { ensureResourceUnmarked(it, workConfiguration, "No current candidates for deletion") }
      log.debug("Nothing to delete. No upstream resources. Configuration: {}", workConfiguration.toLog())
      return
    }

    val processedCandidates = candidates
      .filter { candidate ->
        currentMarkedResourcesToDelete.any { r ->
          candidate.resourceId == r.resourceId
        }
      }
      .shuffled()
      .withResolvedOwners(workConfiguration)
      .also { preProcessCandidates(it, workConfiguration) }

    val confirmedResourcesToDelete = currentMarkedResourcesToDelete.filter { resource ->
      var shouldSkip = false
      val candidate = processedCandidates.find { it.resourceId == resource.resourceId }
      if ((candidate == null || getViolations(candidate, workConfiguration).isEmpty()) ||
        shouldExcludeResource(candidate, workConfiguration, optedOutResourceStates, Action.DELETE)) {
        shouldSkip = true
        ensureResourceUnmarked(resource, workConfiguration, "Resource no longer qualifies for deletion")
      }
      !shouldSkip
    }

    val excludedCount = currentMarkedResourcesToDelete.size - confirmedResourcesToDelete.size
    val candidateCounter = AtomicInteger(0)
    val maxItemsToProcess = min(confirmedResourcesToDelete.size, workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService))
    confirmedResourcesToDelete.subList(0, maxItemsToProcess).let {
      partitionList(it, workConfiguration).forEach { partition ->
        if (partition.isNotEmpty()) {
          try {
            deleteResources(partition, workConfiguration)
            partition.forEach { markedResource ->
              applicationEventPublisher.publishEvent(DeleteResourceEvent(markedResource, workConfiguration))
              resourceRepository.remove(markedResource)
            }

            candidateCounter.addAndGet(partition.size)
          } catch (e: Exception) {
            log.error("Failed to delete $it. Configuration: {}", workConfiguration.toLog(), e)
            recordFailureForAction(Action.DELETE, workConfiguration, e)
          }
        }
      }
    }

    recordCandidateCount(workConfiguration, Action.DELETE, candidates.size)
    recordExcludedResources(workConfiguration, Action.DELETE, excludedCount)

    Action.DELETE.printResult(workConfiguration, processed = candidateCounter.get(), excluded = excludedCount, total = candidates.size)
  }

  private fun recordCandidateCount(workConfiguration: WorkConfiguration, action: Action, count: Int) {
    registry.gauge(
      resourcesVisitedId.withTags(
        "resourceType", workConfiguration.resourceType,
        "location", workConfiguration.location,
        "account", workConfiguration.account.name,
        "action", action.name
      )).set(count.toDouble())
  }

  private fun recordExcludedResources(workConfiguration: WorkConfiguration, action: Action, count: Int) {
    registry.gauge(
      resourcesExcludedId.withTags(
        "resourceType", workConfiguration.resourceType,
        "location", workConfiguration.location,
        "account", workConfiguration.account.name,
        "action", action.name
      )).set(count.toDouble())
  }

  private fun recordFailureForAction(action: Action, workConfiguration: WorkConfiguration, e: Exception) {
    registry.counter(
      resourceFailureId.withTags(
        "resourceType", workConfiguration.resourceType,
        "location", workConfiguration.location,
        "account", workConfiguration.account.name,
        "action", action.name,
        "exception", e.javaClass.simpleName
      )).increment()
  }

  /**
   * Partitions the list of marked resources to process:
   * For convenience, partitions are grouped by relevance
   */
  fun partitionList(
    markedResources: List<MarkedResource>,
    configuration: WorkConfiguration
  ): List<List<MarkedResource>> {
    val partitions = mutableListOf<List<MarkedResource>>()
    markedResources.groupBy {
      it.resource.grouping
    }.map {
      Lists.partition(it.value, configuration.itemsProcessedBatchSize).forEach { partition ->
        partitions.add(partition)
      }
    }

    return partitions.sortedByDescending { it.size }
  }

  override fun getViolations(resource: T, workConfiguration: WorkConfiguration): List<Summary> {
    return rulesEngine.evaluate(resource, workConfiguration)
  }

  /**
   * Adds the owner to the resourceOwnerField, for each candidate, and if no owner is found it's resolved to the
   * "defaultDestination", defined in config
   */
  private fun List<T>.withResolvedOwners(workConfiguration: WorkConfiguration): List<T> =
    this.map { candidate ->
      candidate.apply {
        set(
          resourceOwnerField,
          ownerResolver.resolve(candidate) ?: workConfiguration.notificationConfiguration.defaultDestination
        )
      }
    }

  /**
   * @return Marked resources meeting the deletion criteria:
   * - They are due for deletion
   * - The have been notified on if notifications are enabled
   */
  private fun WorkConfiguration.getMarkedResourcesToDelete(): List<MarkedResource> {
    return resourceRepository.getMarkedResourcesToDelete()
      .filter {
        it.namespace == namespace && (!notificationConfiguration.enabled || it.notificationInfo != null)
      }
      .sortedBy { it.resource.createTs }
  }

  /**
   * @return Marked resources to notify on
   */
  private fun WorkConfiguration.getMarkedResourcesToNotify(): List<MarkedResource> {
    return resourceRepository.getMarkedResources()
      .filter {
        namespace == it.namespace && (it.notificationInfo == null)
      }
      .sortedBy { it.resource.createTs }
  }

  /**
   * Filters out excluded and opted out resources.
   * Returns at most [WorkConfiguration.maxItemsProcessedPerCycle] resources
   */
  private fun List<MarkedResource>.filterExcludedAndOptedOut(
    workConfiguration: WorkConfiguration
  ): List<MarkedResource> {
    val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
      .filter {
        it.markedResource.namespace == workConfiguration.namespace && it.optedOut
      }
    val maxItemsToProcess = min(size, workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService))
    return subList(0, maxItemsToProcess)
      .filter {
        @Suppress("UNCHECKED_CAST")
        !shouldExcludeResource(it.resource as T, workConfiguration, optedOutResourceStates, Action.NOTIFY)
      }
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this
}
