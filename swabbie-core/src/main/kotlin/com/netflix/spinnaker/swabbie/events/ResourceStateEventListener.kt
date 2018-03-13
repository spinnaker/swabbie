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

import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.ResourceStateRepository
import com.netflix.spinnaker.swabbie.ResourceTagger
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.humanReadableDeletionTime
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ResourceStateEventListener(
  private val resourceStateRepository: ResourceStateRepository,
  private val clock: Clock,
  @Autowired(required = false) private val resourceTagger: ResourceTagger?
) {
  @EventListener(MarkResourceEvent::class)
  fun onMarkResourceEvent(event: MarkResourceEvent) {
    event.let { e->
      updateState(e)
      resourceTagger?.tag(
        markedResource = e.markedResource,
        workConfiguration = event.workConfiguration,
        description = "${e.markedResource.typeAndName()} scheduled to be cleaned up on ${e.markedResource.humanReadableDeletionTime(clock)}"
      )
    }
  }

  @EventListener(UnMarkResourceEvent::class)
  fun onUnMarkResourceEvent(event: MarkResourceEvent) {
    event.let { e ->
      updateState(e)
      resourceTagger?.unTag(
        markedResource = e.markedResource,
        workConfiguration = event.workConfiguration,
        description = "${e.markedResource.typeAndName()}. No longer a cleanup candidate"
      )
    }
  }

  @EventListener(DeleteResourceEvent::class)
  fun onDeleteResourceEvent(event: DeleteResourceEvent) {
    event.let { e ->
      updateState(e)
      resourceTagger?.unTag(
        markedResource = e.markedResource,
        workConfiguration = event.workConfiguration,
        description = "Removing tag for now deleted ${e.markedResource.typeAndName()}"
      )
    }
  }

  @EventListener(OwnerNotifiedEvent::class)
  fun onNotifyOwnerEvent(event: OwnerNotifiedEvent) {
    event.let { e ->
      updateState(e)
      resourceTagger?.tag(
        markedResource = e.markedResource,
        workConfiguration = event.workConfiguration,
        description = "Notified ${e.markedResource.notificationInfo.recipient} about soon to be cleaned up ${e.markedResource.typeAndName()}"
      )
    }
  }

  private fun updateState(event: Event) {
    event.markedResource.let {
      resourceStateRepository.get(
        resourceId = it.resourceId,
        namespace = it.namespace
      ).let { currentState ->
        currentState?.statuses?.add(Status(event.name, clock.instant().toEpochMilli()))
        (currentState?.copy(
          statuses = currentState.statuses,
          markedResource = it
        ) ?: ResourceState(
          markedResource = it,
          statuses = mutableListOf(Status(event.name, clock.instant().toEpochMilli()))
        )).let {
          resourceStateRepository.upsert(it)
        }
      }
    }
  }
}

internal fun MarkedResource.typeAndName(): String = this.resourceType.split("(?=[A-Z])".toRegex()).joinToString(" ") + ": " + this.name
