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
import com.netflix.spinnaker.swabbie.model.Work
import com.netflix.spinnaker.swabbie.LockManager
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.messageSubjectAndBody
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.Notifier
import com.netflix.spinnaker.swabbie.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
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
  private val work: List<Work>,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val discoverySupport: DiscoverySupport,
  private val clock: Clock,
  private val notifier: Notifier
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
        markedResourcesGroupedByOwner().forEach { owner ->
          owner.takeIf {
            lockManager.acquire(locksName(PREFIX, it.key), lockTtlSeconds = 3600)
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
                    notificationType = "EMAIL"
                  )
                }

                log.info("Adjusting deletion time to {} for {}", offset + markedResource.projectedDeletionStamp, markedResource)
                Pair(markedResource, markedResourceAndConfiguration.second)
              }.let { markedResourceAndConfiguration ->
                  val resources = markedResourceAndConfiguration.map { it.first }.toList()
                  val (subject, body) = messageSubjectAndBody(resources, clock)
                  notifier.notify(owner.key, subject, body, "EMAIL").let {
                    resources.forEach { resource ->
                      log.info("notification sent to {} for {}", owner.key, resources)
                      resourceTrackingRepository.upsert(resource, resource.adjustedDeletionStamp!!)
                      applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, resource.scopeOfWorkConfiguration(markedResourceAndConfiguration)))
                      lockManager.acquire(locksName(PREFIX, owner.key), lockTtlSeconds = 3600)
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

  private fun markedResourcesGroupedByOwner(): MutableMap<String, MutableList<Pair<MarkedResource, WorkConfiguration>>> {
    val owners = mutableMapOf<String, MutableList<Pair<MarkedResource, WorkConfiguration>>>()
    resourceTrackingRepository.getMarkedResources()
      ?.filter {
        it.notificationInfo.notificationStamp == null && it.adjustedDeletionStamp == null
      }?.forEach { markedResource ->
        markedResource.resourceOwner
          .let { owner ->
            if (owner != null) {
              getMatchingConfiguration(markedResource)?.let { config ->
                if (owners[owner] == null) {
                  owners[owner] = mutableListOf(Pair(markedResource, config))
                } else {
                  owners[owner]!!.add(Pair(markedResource, config))
                }
              }
            } else {
              log.error("unable to find owner for resource {}", markedResource)
            }
          }
      }
    return owners
  }

  private fun getMatchingConfiguration(markedResource: MarkedResource): WorkConfiguration? = work.find { it.namespace == markedResource.namespace }?.configuration
}

private val PREFIX = "{swabbie:notify}"
private fun MarkedResource.scopeOfWorkConfiguration(configs: List<Pair<MarkedResource, WorkConfiguration>>): WorkConfiguration = configs.find { this == it.first }!!.second
