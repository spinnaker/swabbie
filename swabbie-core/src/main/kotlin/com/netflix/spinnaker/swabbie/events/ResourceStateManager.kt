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

package com.netflix.spinnaker.swabbie.events

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ResourceStateManager(
  private val resourceStateRepository: ResourceStateRepository,
  private val clock: Clock,
  private val registry: Registry,
  @Autowired(required = false) private val resourceTagger: ResourceTagger?
) {
  private val log = LoggerFactory.getLogger(javaClass)

  private val markCountId: Id = registry.createId("swabbie.resources.markCount")
  private val unMarkCountId: Id = registry.createId("swabbie.resources.unMarkCount")
  private val deleteCountId: Id = registry.createId("swabbie.resources.deleteCount")
  private val notifyCountId: Id = registry.createId("swabbie.resources.notifyCount")
  private val optOutCountId: Id = registry.createId("swabbie.resources.optOutCount")
  private val orcaTaskFailureId: Id = registry.createId("swabbie.resources.orcaTaskFailureCount")

  @EventListener
  fun handleEvents(event: Event) {
    val workConfiguration = event.workConfiguration
    val markedResource = event.markedResource
    val normalizedName = markedResource.typeAndName()
    val deletionDate = markedResource.deletionDate(clock)
    val notifiedOwner = markedResource.notificationInfo?.recipient

    when (event) {
      is MarkResourceEvent -> {
        registry.counter(withTags(markCountId, workConfiguration)).increment()
        resourceTagger?.tag(
          markedResource,
          workConfiguration,
          description = "$normalizedName is scheduled to be deleted on $deletionDate")
      }

      is UnMarkResourceEvent -> {
        registry.counter(withTags(unMarkCountId, workConfiguration)).increment()
        resourceTagger?.unTag(
          markedResource,
          workConfiguration,
          description = "$normalizedName is no longer a cleanup candidate")
      }

      is OwnerNotifiedEvent -> {
        registry.counter(withTags(notifyCountId, workConfiguration)).increment()
        resourceTagger?.tag(
          markedResource,
          workConfiguration,
          description = "Notified $notifiedOwner about soon to be deleted $normalizedName")
      }

      is OptOutResourceEvent -> {
        registry.counter(withTags(optOutCountId, workConfiguration)).increment()
        resourceTagger?.unTag(
          markedResource,
          workConfiguration,
          description = "$normalizedName is opted out of deletion")
      }

      is DeleteResourceEvent -> {
        registry.counter(withTags(deleteCountId, workConfiguration)).increment()
        resourceTagger?.unTag(
          markedResource,
          workConfiguration,
          description = "Removing tag for now deleted $normalizedName")
      }

      is OrcaTaskFailureEvent -> {
        registry.counter(withTags(orcaTaskFailureId, workConfiguration)).increment()
      }

      else -> log.warn("Unknown event type: ${event.javaClass.simpleName}")
    }

    updateState(event)
  }

  private fun updateState(event: Event) {
    val currentState = resourceStateRepository.get(
      resourceId = event.markedResource.resourceId,
      namespace = event.markedResource.namespace
    )
    val statusName = if (event is OrcaTaskFailureEvent) "${event.action.name} FAILED" else event.action.name
    val status = Status(statusName, clock.instant().toEpochMilli())

    currentState?.statuses?.add(status)
    val newState = (currentState?.copy(
      statuses = currentState.statuses,
      markedResource = event.markedResource,
      deleted = event is DeleteResourceEvent,
      optedOut = event is OptOutResourceEvent,
      currentStatus = status
    ) ?: ResourceState(
      markedResource = event.markedResource,
      deleted = event is DeleteResourceEvent,
      optedOut = event is OptOutResourceEvent,
      statuses = mutableListOf(status),
      currentStatus = status
    ))

    resourceStateRepository.upsert(newState)
  }

  private fun withTags(id: Id, workConfiguration: WorkConfiguration): Id {
    return id.withTags(
      "resourceType", workConfiguration.resourceType,
      "location", workConfiguration.location,
      "account", workConfiguration.account.name,
      "configuration", workConfiguration.namespace
    )
  }
}

internal fun MarkedResource.typeAndName(): String {
  if (name == null || name == resourceId) {
    resourceId
  } else {
    "($resourceId) $name"
  }.let { suffix ->
    return resourceType
      .split("(?=[A-Z])".toRegex())
      .joinToString(" ") + ": $suffix"
  }
}
