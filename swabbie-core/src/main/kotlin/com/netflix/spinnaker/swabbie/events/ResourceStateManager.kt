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
import com.netflix.spinnaker.swabbie.ResourceStateRepository
import com.netflix.spinnaker.swabbie.ResourceTagger
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
) {

  @EventListener
  fun handleEvents(event: Event) {
    var id: Id? = null
    var msg: String? = null
    var removeTag = false

    when (event) {
      is MarkResourceEvent -> {
        id = markCountId
        msg = "${event.markedResource.typeAndName()} scheduled to be cleaned up on ${event.markedResource.humanReadableDeletionTime(clock)}"
      }

      is UnMarkResourceEvent -> {
        id = unMarkCountId
        removeTag = true
        msg = "${event.markedResource.typeAndName()}. No longer a cleanup candidate"
      }
      is DeleteResourceEvent -> {
        id = deleteCountId
        removeTag = true
        msg = "Removing tag for now deleted ${event.markedResource.typeAndName()}"
      }
      is OwnerNotifiedEvent -> {
        id = notifyCountId
        removeTag = false
        msg = "Notified ${event.markedResource.notificationInfo?.recipient} about soon to be cleaned up ${event.markedResource.typeAndName()}"
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

    if (resourceTagger != null && id != null && msg != null) {
      tag(resourceTagger, event, msg, removeTag)
    }
  }

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
    event.markedResource.let {
      resourceStateRepository.get(
        resourceId = it.resourceId,
        namespace = it.namespace
      ).let { currentState ->
        currentState?.statuses?.add(Status(event.action.name, clock.instant().toEpochMilli()))
        (currentState?.copy(
          statuses = currentState.statuses,
          markedResource = it,
          deleted = event is DeleteResourceEvent
        ) ?: ResourceState(
          markedResource = it,
          deleted = event is DeleteResourceEvent,
          statuses = mutableListOf(Status(event.action.name, clock.instant().toEpochMilli()))
        )).let {
          resourceStateRepository.upsert(it)
        }
      }
    }
  }

  private val markCountId = registry.createId("swabbie.resources.markCount")
  private val unMarkCountId = registry.createId("swabbie.resources.unMarkCount")
  private val deleteCountId = registry.createId("swabbie.resources.deleteCount")
  private val notifyCountId = registry.createId("swabbie.resources.notifyCount")
}

internal fun MarkedResource.typeAndName(): String = this.resourceType.split("(?=[A-Z])".toRegex()).joinToString(" ") + ": " + this.name
internal fun String.formatted(): String = this.split("(?=[A-Z])".toRegex()).joinToString(" ").toLowerCase()
