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
import com.netflix.spinnaker.swabbie.DiscoverySupport
import com.netflix.spinnaker.swabbie.MessageType
import com.netflix.spinnaker.swabbie.NotificationMessage
import com.netflix.spinnaker.swabbie.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.echo.EchoService
import com.netflix.spinnaker.swabbie.echo.Notifier
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.work.Processor
import com.netflix.spinnaker.swabbie.work.WorkConfiguration
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
  workProcessor: Processor,
  discoverySupport: DiscoverySupport,
  private val clock: Clock,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val notifier: Notifier
) : ScheduledAgent(clock, registry, workProcessor, discoverySupport) {
  @Value("\${swabbie.optOut.url}")
  lateinit var optOutUrl: String

  @Value("\${swabbie.agents.notify.intervalSeconds:3600}")
  private var interval: Long = 3600
  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())
  private val lastNotifierAgentRun: Instant
    get() = _lastAgentRun.get()

  override fun getLastAgentRun(): Temporal? = lastNotifierAgentRun
  override fun getAgentFrequency(): Long = interval
  override fun setLastAgentRun(instant: Instant) {
    _lastAgentRun.set(instant)
  }

  override fun initializeAgent() {
    log.info("Notification agent starting")
  }

  private val notificationsId = registry.createId("swabbie.notifications")
  override fun process(workConfiguration: WorkConfiguration, complete: () -> Unit) {
    try {
      log.info("Notification Agent Started ...")
      getResourcesGroupedByOwner(workConfiguration).forEach { ownerToResources ->
        ownerToResources.value.map { markedResource ->
          val notificationInstant = Instant.now(clock)
          val offset: Long = ChronoUnit.MILLIS.between(Instant.ofEpochMilli(markedResource.createdTs!!), notificationInstant)
          markedResource.apply {
            this.adjustedDeletionStamp = offset + markedResource.projectedDeletionStamp
            this.notificationInfo = NotificationInfo(
              notificationStamp = notificationInstant.toEpochMilli(),
              recipient = ownerToResources.key,
              notificationType = EchoService.Notification.Type.EMAIL.name
            )
          }
          ownerToResources
        }.map {
            val resources = ownerToResources.value
            val subject = NotificationMessage.subject(MessageType.EMAIL, clock, *resources.toTypedArray())
            val body = NotificationMessage.body(MessageType.EMAIL, clock, optOutUrl, *resources.toTypedArray())
            notifier.notify(it.key, subject, body, "EMAIL").let {
              resources.forEach { resource ->
                resource.takeIf {
                  !workConfiguration.dryRun
                }?.let {
                    log.info("notification sent to {} for {}", ownerToResources.key, resources)
                    resourceTrackingRepository.upsert(resource, resource.adjustedDeletionStamp!!)
                    applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, workConfiguration))
                  }
              }
            }

            complete()
          }
      }
    } catch (e: Exception) {
      log.error("Failed to run notification agent", e)
      registry.counter(failedAgentId.withTags("agentName", this.javaClass.simpleName, "configuration", workConfiguration.namespace)).increment()
      registry.counter(notificationsId.withTags("failed")).increment()
    }
  }

  private fun getResourcesGroupedByOwner(workConfiguration: WorkConfiguration): Map<String, MutableList<MarkedResource>> {
    val owners = mutableMapOf<String, MutableList<MarkedResource>>()
    resourceTrackingRepository.getMarkedResources()
      ?.filter {
        workConfiguration.namespace.equals(it.namespace, ignoreCase = true) && it.notificationInfo.notificationStamp == null && it.adjustedDeletionStamp == null
      }?.forEach { markedResource ->
        markedResource.resourceOwner?.let { owner ->
          if (owners[owner] == null) {
            owners[owner] = mutableListOf(markedResource)
          } else {
            owners[owner]!!.add(markedResource)
          }
        }
      }

    return owners
  }
}
