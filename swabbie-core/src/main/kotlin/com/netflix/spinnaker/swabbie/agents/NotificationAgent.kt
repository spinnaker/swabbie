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

package com.netflix.spinnaker.swabbie.agents

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.ScheduledAgent
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.echo.EchoService
import com.netflix.spinnaker.swabbie.echo.Notifier
import com.netflix.spinnaker.swabbie.model.Work
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import java.util.concurrent.atomic.AtomicReference

/**
 * Groups owner to listOf(markedResource) with matching configuration
 * finds a user and its related resources and send a single notification
 */
@Component
@ConditionalOnExpression("\${swabbie.agents.notify.enabled}")
class NotificationAgent(
  registry: Registry,
  discoverySupport: DiscoverySupport,
  private val clock: Clock,
  private val lockManager: LockManager,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val work: List<Work>,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val notifier: Notifier
): ScheduledAgent(clock, registry, discoverySupport) {
  @Value("\${swabbie.optOut.url}")
  lateinit var optOutUrl: String

  @Value("\${swabbie.agents.notify.intervalSeconds:3600000}")
  private var interval: Long = 3600000
  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())
  private val lastNotifierAgentRun: Instant
    get() = _lastAgentRun.get()

  override val agentName: String
    get() = "NOTIFIER"

  override fun getLastAgentRun(): Temporal? = lastNotifierAgentRun
  override fun getAgentFrequency(): Long = interval
  override fun setLastAgentRun(instant: Instant) {
    _lastAgentRun.set(instant)
  }

  override fun initializeAgent() {
    log.info("Notification agent starting")
  }

  private val notificationsId = registry.createId("swabbie.notifications")
  override fun run() {
    var currentConfiguration: WorkConfiguration? = null
    try {
      log.info("Notification Agent Started ...")
      markedResourcesGroupedByOwner().forEach { owner ->
        owner.takeIf {
          lockManager.acquire(locksName(it.key), lockTtlSeconds = 3600)
        }.let {
            owner.value.map { markedResourceAndConfiguration ->
              val markedResource = markedResourceAndConfiguration.first
              val notificationInstant = Instant.now(clock)
              val offset: Long = ChronoUnit.MILLIS.between(Instant.ofEpochMilli(markedResource.createdTs!!), notificationInstant)
              markedResource.apply {
                this.adjustedDeletionStamp = offset + markedResource.projectedDeletionStamp
                this.notificationInfo = NotificationInfo(
                  notificationStamp = notificationInstant.toEpochMilli(),
                  recipient = owner.key,
                  notificationType = EchoService.Notification.Type.EMAIL.name
                )
              }

              log.info("Adjusting deletion time to {} for {}", offset + markedResource.projectedDeletionStamp, markedResource)
              Pair(markedResource, markedResourceAndConfiguration.second)
            }.let { markedResourceAndConfiguration ->
                val resources = markedResourceAndConfiguration.map { it.first }.toList()
                val subject = NotificationMessage.subject(MessageType.EMAIL, clock, *resources.toTypedArray())
                val body = NotificationMessage.body(MessageType.EMAIL, clock, optOutUrl, *resources.toTypedArray())
                notifier.notify(owner.key, subject, body, "EMAIL").let {
                  resources.forEach { resource ->
                    currentConfiguration = resource.workConfiguration(markedResourceAndConfiguration)
                    resource.takeIf {
                      !currentConfiguration!!.dryRun
                    }?.let {
                        log.info("notification sent to {} for {}", owner.key, resources)
                        resourceTrackingRepository.upsert(resource, resource.adjustedDeletionStamp!!)
                        applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, resource.workConfiguration(markedResourceAndConfiguration)))
                      }
                  }
                }

                lockManager.release(locksName(owner.key))
              }
          }
      }
    } catch (e: Exception) {
      log.error("Failed to run notification agent", e)
      registry.counter(
        failedAgentId.withTags(
          "agentName", agentName,
          "configuration", currentConfiguration?.namespace ?: "unknown"
        )
      ).increment()
      registry.counter(notificationsId.withTags("failed")).increment()
    }
  }

  private fun markedResourcesGroupedByOwner(): MutableMap<String, MutableList<Pair<MarkedResource, WorkConfiguration>>> {
    val owners = mutableMapOf<String, MutableList<Pair<MarkedResource, WorkConfiguration>>>()
    resourceTrackingRepository.getMarkedResources()
      ?.filter {
        it.notificationInfo.notificationStamp == null && it.adjustedDeletionStamp == null
      }?.forEach { markedResource ->
        markedResource.resourceOwner?.let { owner ->
          getMatchingConfiguration(markedResource)?.let { config ->
            if (owners[owner] == null) {
              owners[owner] = mutableListOf(Pair(markedResource, config))
            } else {
              owners[owner]!!.add(Pair(markedResource, config))
            }
          }
        }
      }

    return owners
  }

  private fun getMatchingConfiguration(markedResource: MarkedResource): WorkConfiguration?
    = work.find { it.namespace == markedResource.namespace }?.configuration
}

private fun MarkedResource.workConfiguration(configs: List<Pair<MarkedResource, WorkConfiguration>>): WorkConfiguration
  = configs.find { this == it.first }!!.second
