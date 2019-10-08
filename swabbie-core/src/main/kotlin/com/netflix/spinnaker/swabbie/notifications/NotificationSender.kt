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
import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.discovery.DiscoveryActivated
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationResult
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
  private val notifier: Notifier,
  private val clock: Clock,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val notificationQueue: NotificationQueue,
  private val registry: Registry,
  private val workConfigurations: List<WorkConfiguration>
) : DiscoveryActivated() {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val notificationsId = registry.createId("swabbie.notifications")

  private val lockOptions = LockManager.LockOptions()
    .withLockName(lockingService.ownerName)
    .withMaximumLockDuration(lockingService.swabbieMaxLockDuration)

  /**
   * Periodically sends notifications to resolved resource owners
   * The frequency of this scheduled action is cron based and configurable
   * Defaults to every day at 9am.
   */
  @Scheduled(cron = "\${swabbie.notification.cron.schedule:0 0 9 1/1 * ?}")
  fun sendNotifications() {
    if (!isUp()) {
      return
    }

    lockingService.acquireLock(lockOptions) {
      try {
        val resources = resourceTrackingRepository.getMarkedResources()
          .filter {
            it.notificationInfo == null
          }

        val notificationTasks = notificationQueue.popAll()
        for (task in notificationTasks) {
          val resourceType = task.resourceType
          val notificationConfiguration = getNotificationConfigurationForTypeOrNull(resourceType)
          if (notificationConfiguration == null) {
            log.warn("Resource Type {} not enabled.", resourceType)
            continue
          }

          val resourcesByOwner = notificationResourcesByOwner(resources, resourceType)
          resourcesByOwner.forEach { (owner, resourcesAndConfigs) ->
            val result = notifyUser(owner, resourceType, resourcesAndConfigs, notificationConfiguration)
            handleNotificationResult(result, resourcesAndConfigs)
          }
        }
      } catch (e: Exception) {
        log.error("Failed to process notifications", e)
      }
    }
  }

  private fun handleNotificationResult(
    result: NotificationResult,
    resourcesAndConfigs: List<Pair<MarkedResource, WorkConfiguration>>
  ) {
    if (!result.success) {
      registry.counter(notificationsId.withTags("result", "failure")).increment()
      return
    }

    registry.counter(notificationsId.withTags("result", "success")).increment()

    val now = Instant.now(clock)
    val notificationInfo = NotificationInfo(
      result.recipient,
      result.notificationType.name,
      now.toEpochMilli()
    )

    resourcesAndConfigs
      .forEach { (resource, workConfiguration) ->
        val elapsedTimeSinceMarked = Instant.ofEpochMilli(resource.markTs!!).until(now, ChronoUnit.MILLIS)
        resource
          .withNotificationInfo(notificationInfo)
          .withAdditionalTimeForDeletion(elapsedTimeSinceMarked)
          .also {
            resourceTrackingRepository.upsert(resource)
            applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, workConfiguration))
          }
      }
  }

  private fun notificationResourcesByOwner(resources: List<MarkedResource>, resourceType: String): Map<String, List<Pair<MarkedResource, WorkConfiguration>>> {
    val resourcesByOwner = resources
      .filterByResourceType(resourceType)
      .toResourceAndConfigurationPairs()
      .groupBy {
        it.first.resourceOwner
      }
    return resourcesByOwner
  }

  private fun getNotificationConfigurationForTypeOrNull(resourceType: String): NotificationConfiguration? {
    return workConfigurations.find {
      it.resourceType == resourceType
    }?.notificationConfiguration
  }

  private fun notifyUser(
    owner: String,
    resourceType: String,
    resourcesAndConfigs: List<Pair<MarkedResource, WorkConfiguration>>,
    notificationConfiguration: NotificationConfiguration
  ): NotificationResult {
    return notifier.notify(
      recipient = owner,
      notificationContext = notificationContext(owner, resourcesAndConfigs, resourceType),
      notificationConfiguration = notificationConfiguration
    )
  }

  private fun List<MarkedResource>.filterByResourceType(resourceType: String): List<MarkedResource> {
    return filter {
      it.resourceType == resourceType
    }
  }

  private fun List<MarkedResource>.toResourceAndConfigurationPairs(): List<Pair<MarkedResource, WorkConfiguration>> {
    return mapNotNull { resource ->
      val workConfiguration: WorkConfiguration? = workConfigurations.find {
        it.namespace == resource.namespace
      }

      if (workConfiguration == null) {
        log.debug("Skipping ${resource.resourceId}. Reasons: ${resource.resourceType} currently disabled in the configuration.")
        null
      } else {
        Pair(resource, workConfiguration)
      }
    }
  }

  private fun String.unCamelCase(): String =
    split("(?=[A-Z])".toRegex()).joinToString(" ").toLowerCase()

  private fun notificationContext(
    recipient: String,
    resources: List<Pair<MarkedResource, WorkConfiguration>>,
    resourceType: String
  ): Map<String, Any> {
    return mapOf(
      "resourceType" to resourceType.unCamelCase(), // TODO: Jeyrs - normalize resource type so we wont have to do this
      "resourceOwner" to recipient,
      "resources" to resources
    )
  }
}
