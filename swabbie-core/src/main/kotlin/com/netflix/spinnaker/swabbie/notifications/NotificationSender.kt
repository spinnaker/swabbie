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
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.discovery.DiscoveryAware
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
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
) : DiscoveryAware() {
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
      val resources = resourceTrackingRepository.getMarkedResources()
        .filter {
          it.notificationInfo == null
        }

      groupRelatedResourcesThenNotify(resources)
    }
  }

  /**
   * @return Marked resources to notify on
   */
  private fun groupRelatedResourcesThenNotify(markedResources: List<MarkedResource>) {
    val now = Instant.now(clock)
    val tasks = mutableListOf<NotificationTask>()
    do {
      val notificationTask: NotificationTask? = notificationQueue.pop()
      if (notificationTask == null) {
        log.debug("Nothing to notify. Skipping...")
        return
      }

      tasks.add(notificationTask)
    } while (!notificationQueue.isEmpty())

    tasks.forEach { task ->
      val byResourceOwner = markedResources
        .filter {
          it.resourceType == task.resourceType
        }.groupBy {
          it.resourceOwner
        }

      byResourceOwner.forEach { (owner, list) ->
        val resourcesAndConfigurations: List<Pair<MarkedResource, WorkConfiguration>> = list.toResourceAndConfigurationPairs()
        val result = notifier.notify(
          Notifier.Envelope(
            recipient = owner,
            resources = resourcesAndConfigurations
          )
        )

        if (result.success) {
          log.debug("Sent notification to {} for {}:{}",
            owner, task.resourceType, markedResources.size)
        }

        resourcesAndConfigurations.forEach { (resource, workConfiguration) ->
          if (result.success) {
            // Update resource's notification info
            resource
              .withNotificationInfo(
                NotificationInfo(result.recipient, result.notificationType.name, now.toEpochMilli())
              )
              .withAdditionalTimeForDeletion(
                Instant.ofEpochMilli(resource.markTs!!).until(now, ChronoUnit.MILLIS)
              ).also {
                resourceTrackingRepository.upsert(it)
                applicationEventPublisher.publishEvent(OwnerNotifiedEvent(it, workConfiguration))
              }

            registry.counter(notificationsId.withTags("result", "success")).increment()
          } else {
            // unmark this resource & let it go through the mark cycle later
            registry.counter(notificationsId.withTags("result", "failure")).increment()
            resourceTrackingRepository.remove(resource)
            applicationEventPublisher.publishEvent(UnMarkResourceEvent(resource, workConfiguration))
          }
        }
      }
    }
  }

  private fun List<MarkedResource>.toResourceAndConfigurationPairs(): List<Pair<MarkedResource, WorkConfiguration>> {
    return mapNotNull { mr ->
      val workConfiguration: WorkConfiguration? = workConfigurations.find {
        it.namespace == mr.namespace
      }

      if (workConfiguration == null) {
        log.debug("Skipping ${mr.resourceId}. Reasons: ${mr.resourceType} currently disabled in the configuration.")
        null
      } else {
        Pair(mr, workConfiguration)
      }
    }
  }

  data class NotificationTask(
    val resourceType: String,
    val namespace: String
  )
}
