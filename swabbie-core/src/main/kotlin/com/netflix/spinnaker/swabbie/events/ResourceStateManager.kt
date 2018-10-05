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
import com.netflix.spinnaker.swabbie.MetricsSupport
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.humanReadableDeletionTime
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
) : MetricsSupport(registry) {

  @EventListener
  fun handleEvents(event: Event) {
    var id: Id? = null
    var msg: String? = null
    var removeTag = false

    when (event) {
      is MarkResourceEvent -> {
        id = markCountId
        msg = "${event.markedResource.typeAndName()} scheduled to be cleaned up on " +
          "${event.markedResource.humanReadableDeletionTime(clock)}"
      }

      is UnMarkResourceEvent -> {
        id = unMarkCountId
        removeTag = true
        msg = "${event.markedResource.typeAndName()}. No longer a cleanup candidate"
      }

      is OwnerNotifiedEvent -> {
        id = notifyCountId
        removeTag = false
        msg = "Notified ${event.markedResource.notificationInfo?.recipient} about soon to be cleaned up " +
          event.markedResource.typeAndName()
      }

      is OptOutResourceEvent -> {
        id = optOutCountId
        removeTag = true
        msg = "${event.markedResource.typeAndName()}. Opted Out"
      }

      is SoftDeleteResourceEvent -> {
        id = softDeleteCountId
        msg = "Soft deleted resource ${event.markedResource.typeAndName()}"
      }

      is RestoreResourceEvent -> {
        id = restoreCountId
        msg = "Restored resource ${event.markedResource.typeAndName()}"
      }

      is DeleteResourceEvent -> {
        id = deleteCountId
        removeTag = true
        msg = "Removing tag for now deleted ${event.markedResource.typeAndName()}"
      }

      is OrcaTaskFailureEvent -> {
        id = orcaTAskFailureId
        removeTag = false
        msg = generateFailureMessage(event)
        //todo eb: do we want this tagged here?
      }
    }

    updateState(event)
    id?.let {
      registry.counter(
        it.withTags(
          "configuration", event.workConfiguration.namespace,
          "resourceType", event.workConfiguration.resourceType
        )
      ).increment()
    }

    if (resourceTagger != null && msg != null) {
      tag(resourceTagger, event, msg, removeTag)
    }
  }

  fun generateFailureMessage(event: Event) =
    "Task failure for action ${event.action} on resource ${event.markedResource.typeAndName()}"

  private fun tag(tagger: ResourceTagger, event: Event, msg: String, remove: Boolean = false) {
    if (!remove) {
      tagger.tag(
        markedResource = event.markedResource,
        workConfiguration = event.workConfiguration,
        description = msg
      )
    } else {
      tagger.unTag(
        markedResource = event.markedResource,
        workConfiguration = event.workConfiguration,
        description = msg
      )
    }
  }

  private fun updateState(event: Event) {
    event.markedResource.let { markedResource ->
      resourceStateRepository.get(
        resourceId = markedResource.resourceId,
        namespace = markedResource.namespace
      ).let { currentState ->
        val statusName = if (event is OrcaTaskFailureEvent) "${event.action.name} FAILED" else event.action.name
        val status = Status(statusName, clock.instant().toEpochMilli())
        currentState?.statuses?.add(status)
        (currentState?.copy(
          statuses = currentState.statuses,
          markedResource = markedResource,
          softDeleted = event is SoftDeleteResourceEvent,
          deleted = event is DeleteResourceEvent,
          optedOut = event is OptOutResourceEvent,
          currentStatus = status
        ) ?: ResourceState(
          markedResource = markedResource,
          softDeleted = event is SoftDeleteResourceEvent,
          deleted = event is DeleteResourceEvent,
          optedOut = event is OptOutResourceEvent,
          statuses = mutableListOf(status),
          currentStatus = status
        )).let {
          resourceStateRepository.upsert(it)
        }
      }
    }
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

internal fun String.formatted(): String
  = this.split("(?=[A-Z])".toRegex()).joinToString(" ").toLowerCase()
