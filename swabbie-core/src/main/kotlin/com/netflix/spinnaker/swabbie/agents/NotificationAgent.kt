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
import com.netflix.spinnaker.SwabbieAgent
import com.netflix.spinnaker.swabbie.configuration.ScopeOfWorkConfiguration
import com.netflix.spinnaker.swabbie.configuration.ScopeOfWorkConfigurator
import com.netflix.spinnaker.swabbie.persistence.LockManager
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.notifications.messageSubjectAndBody
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.notifications.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.persistence.ResourceTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
@ConditionalOnExpression("\${swabbie.agents.notify.enabled}")
class NotificationAgent(
  private val lockManager: LockManager,
  private val registry: Registry,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val scopeOfWorkConfigurator: ScopeOfWorkConfigurator,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val discoverySupport: DiscoverySupport,
  private val clock: Clock,
  private val notifier: Notifier,
  private val resourceOwnerResolvers: List<ResourceOwnerResolver>
): SwabbieAgent {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val resourceWithNoOwnerId = registry.createId("swabbie.notifications")

  /**
   * Groups owner to listOf(markedResource) with matching configuration
   * finds a user and its related resources and send a single notification
   */
  @Scheduled(fixedDelayString = "\${swabbie.agents.notify.intervalSeconds:3600000}")
  override fun execute() {
    discoverySupport.ifUP {
      try {
        log.info("Notification Agent Started ...")
        val resourceConfigurations = mutableMapOf<String, ScopeOfWorkConfiguration>()
        val owners = mutableMapOf<String, MutableList<MarkedResource>>()

        // finds resource owner and map configuration for each resource
        resourceTrackingRepository.getMarkedResources()
          ?.filter {
            it.notificationInfo.notificationStamp == null && it.adjustedDeletionStamp == null
          }?.forEach { markedResource ->
            resourceOwnerResolvers.map {
              it.resolve(markedResource.resource)
            }.first()
              .let { owner ->
                if (owner != null) {
                  owners[owner].let { list ->
                    if (list != null) {
                      list += markedResource
                    } else {
                      mutableListOf(markedResource)
                    }
                    getMatchingConfiguration(markedResource)?.let {
                      resourceConfigurations[markedResource.resourceId] = it
                    }
                  }
                } else {
                  registry.counter(resourceWithNoOwnerId.withTag("resolveOwner", "failed"))
                }
            }
        }

        owners.forEach { owner ->
          owner.takeIf {
            lockManager.acquire(locksName(PREFIX, it.key), lockTtlSeconds = 3600)
          }?.let {
            owner.value.mapNotNull { markedResource ->
              resourceConfigurations[markedResource.resourceId]?.let {
                val notificationInstant = Instant.now(clock)
                val offset: Long = ChronoUnit.MILLIS.between(Instant.ofEpochMilli(markedResource.createdTs!!), notificationInstant)
                log.info("Adjusting deletion time to {}", offset + markedResource.projectedDeletionStamp)

                markedResource.apply {
                  this.adjustedDeletionStamp = offset + markedResource.projectedDeletionStamp
                  this.notificationInfo = NotificationInfo(
                    notificationStamp = notificationInstant.toEpochMilli(),
                    recipient = owner.key,
                    notificationType = "EMAIL"
                  )
                }
                Pair(markedResource, it)
              }
            }.let { markedResourceConfigurationPair ->
                if (!markedResourceConfigurationPair.isEmpty()) {
                  val (subject, body) = messageSubjectAndBody(markedResourceConfigurationPair.map { it.first }.toList(), clock)
                  notifier.notify(owner.key, subject, body, "EMAIL").let {
                    markedResourceConfigurationPair.forEach {
                      log.info("notification sent to {} for {}", owner.key)
                      resourceTrackingRepository.upsert(it.first, it.first.adjustedDeletionStamp!!)
                      applicationEventPublisher.publishEvent(OwnerNotifiedEvent(it.first, it.second))
                    }
                  }
                }
              }
          }
        }
      } catch (e: Exception) {
        log.error("Failed to execute notification agent", e)
      }
    }
  }

  private fun getMatchingConfiguration(markedResource: MarkedResource): ScopeOfWorkConfiguration? =
    scopeOfWorkConfigurator.list().find { it.namespace == markedResource.namespace }?.configuration

  private val PREFIX = "{swabbie:notify}"
}
