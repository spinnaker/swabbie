/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.notifications

import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.patterns.PolledMeter
import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.discovery.DiscoveryActivated
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationResult
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A scheduled notification sender
 */
@Component
@ConditionalOnExpression("\${swabbie.enabled:true}")
class NotificationSender(
  private val lockingService: LockingService,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val resourceStateRepository: ResourceStateRepository,
  private val notifier: Notifier,
  private val clock: Clock,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val notificationQueue: NotificationQueue,
  private val registry: Registry,
  private val dynamicConfigService: DynamicConfigService,
  private val workConfigurations: List<WorkConfiguration>
) : DiscoveryActivated() {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val notificationAttemptsId = registry.createId("swabbie.notifications.attempts")
  private val notificationsQueueSizeId = registry.createId("swabbie.notifications.queueSize")

  private val lockOptions = LockManager.LockOptions()
    .withLockName(lockingService.ownerName)
    .withMaximumLockDuration(lockingService.swabbieMaxLockDuration)

  init {
    PolledMeter.using(registry)
      .withId(notificationsQueueSizeId)
      .monitorValue(notificationQueue.size())
  }

  /**
   * Periodically sends notifications to resolved resource owners
   * The frequency of this scheduled action is cron based and configurable
   * Defaults to every day at 9am.
   */
  @Scheduled(cron = "\${swabbie.notification.cron.schedule:0 0/5 * * * ?}")
  fun sendNotifications() {
    if (!isUp()) {
      return
    }

    lockingService.acquireLock(lockOptions) {
      try {
        val resources = getResourcesToNotify()
        val notificationTasks = notificationQueue.popAll()
        for (task in notificationTasks) {
          val resourceType = task.resourceType

          // if we dont find a matching configuration, then this resource type is disabled in the config. Skip it!
          val workConfiguration = findWorkConfigurationForType(resourceType) ?: continue
          val notificationConfiguration = workConfiguration.notificationConfiguration
          val maxItemsToProcess = workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService)

          val resourcesByOwner: Map<String, List<MarkedResource>> = resources
            .filter {
              it.resourceType == resourceType
            }
            .take(maxItemsToProcess)
            .groupBy {
              it.resourceOwner
            }

          for ((owner, list) in resourcesByOwner) {
            val notificationData = list
              .toNotificationData(resourceType)
              .take(notificationConfiguration.itemsPerMessage)

            if (notificationData.isEmpty()) {
              continue
            }

            val result = notifyUser(owner, resourceType, notificationData, workConfiguration.notificationConfiguration)
            if (!result.success) {
              log.warn("Failed to send notification to $owner. Resource type: ${task.resourceType}")
              registry.counter(notificationAttemptsId.withTags("result", "failure")).increment()
              continue
            }

            val notificationInfo = NotificationInfo(
              result.recipient,
              result.notificationType.name,
              Instant.now(clock).toEpochMilli()
            )

            updateNotificationInfoAndPublishEvent(notificationInfo, list)
            registry.counter(notificationAttemptsId.withTags("result", "success")).increment()
          }
        }
      } catch (e: Exception) {
        log.error("Failed to process notifications", e)
      }
    }
  }

  private fun getResourcesToNotify(): List<MarkedResource> {
    // Don't include resources that are opted out, deleted or already notified
    val resourceStates: List<ResourceState> = resourceStateRepository.getAll()
      .filter { state ->
        state.optedOut || state.deleted || state.statuses.any { it.name == Action.NOTIFY.name }
      }

    return resourceTrackingRepository.getMarkedResources()
      .filter { m ->
        m.notificationInfo == null && resourceStates.none { it.markedResource == m }
      }
  }

  private fun updateNotificationInfoAndPublishEvent(
    notificationInfo: NotificationInfo,
    resources: List<MarkedResource>
  ) {
    val now = Instant.now(clock)
    resources
      .forEach { resource ->
        val elapsedTimeSinceMarked = Instant.ofEpochMilli(resource.markTs!!).until(now, ChronoUnit.MILLIS)
        resource
          .withNotificationInfo(notificationInfo)
          .withAdditionalTimeForDeletion(elapsedTimeSinceMarked)
          .also {
            val config = findWorkConfigurationForNamespace(it.namespace)
            resourceTrackingRepository.upsert(it)
            applicationEventPublisher.publishEvent(OwnerNotifiedEvent(it, config!!))
          }
      }
  }

  fun notifyUser(
    owner: String,
    resourceType: String,
    data: List<NotificationResourceData>,
    notificationConfiguration: NotificationConfiguration
  ): NotificationResult {
    return notifier.notify(
      recipient = owner,
      notificationContext = notificationContext(owner, data, resourceType),
      notificationConfiguration = notificationConfiguration
    )
  }

  private fun List<MarkedResource>.toNotificationData(resourceType: String): List<NotificationResourceData> {
    val workConfigurations: Map<String, List<WorkConfiguration>> = workConfigurations.groupBy {
      it.namespace
    }

    return filter {
      it.resourceType == resourceType && workConfigurations.containsKey(it.namespace)
    }.map {
      val config: WorkConfiguration = workConfigurations.getValue(it.namespace).single()
      NotificationResourceData(
        resourceType = resourceType,
        resourceUrl = it.resource.resourceUrl(config),
        account = config.account.name!!,
        location = config.location,
        optOutUrl = it.resource.optOutUrl(config),
        resource = it,
        deletionDate = it.deletionDate(clock).toString()
      )
    }
  }

  private fun findWorkConfigurationForType(resourceType: String): WorkConfiguration? {
    return workConfigurations.find {
      it.resourceType == resourceType
    }
  }

  private fun findWorkConfigurationForNamespace(namespace: String): WorkConfiguration? {
    return workConfigurations.find {
      it.namespace == namespace
    }
  }

  private fun String.unCamelCase(): String =
    split("(?=[A-Z])".toRegex()).joinToString(" ").toLowerCase()

  private fun notificationContext(
    recipient: String,
    resources: List<NotificationResourceData>,
    resourceType: String
  ): Map<String, Any> {
    return mapOf(
      "resourceType" to resourceType.unCamelCase(), // TODO: Jeyrs - normalize resource type so we wont have to do this
      "resourceOwner" to recipient,
      "resources" to resources,
      "slackChannelLink" to "#spinnaker"
    )
  }

  data class NotificationResourceData(
    val resourceType: String,
    val resourceUrl: String,
    val account: String,
    val location: String,
    val optOutUrl: String,
    val resource: MarkedResource,
    val deletionDate: String
  )
}
