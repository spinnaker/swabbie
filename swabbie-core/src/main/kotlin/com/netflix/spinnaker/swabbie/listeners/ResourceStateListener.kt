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

package com.netflix.spinnaker.swabbie.listeners


import com.netflix.spinnaker.swabbie.events.Event
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.persistence.ResourceStateRepository
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ResourceStateListener(
  private val resourceStateRepository: ResourceStateRepository,
  private val clock: Clock,
  @Autowired(required = false) val resourceTagger: ResourceTagger?
) {
  private val log = LoggerFactory.getLogger(javaClass)

  @EventListener(MarkResourceEvent::class)
  fun onMarkResourceEvent(event: MarkResourceEvent) {
    event.let {
      updateState(event)
      resourceTagger?.tag(it.markedResource)
    }
  }

  @EventListener(UnMarkResourceEvent::class)
  fun onUnMarkResourceEvent(event: MarkResourceEvent) {
    event.let {
      updateState(event)
      resourceTagger?.unTag(it.markedResource)
    }
  }

  private fun updateState(event: Event) {
    event.markedResource.let {
      resourceStateRepository.get(it.resourceId, it.configurationId).let { currentState ->
        if (currentState != null) {
          currentState.statuses.add(Status(event.name, clock.instant().toEpochMilli()))
          currentState.markedResource = it
          resourceStateRepository.upsert(currentState)
        } else {
          resourceStateRepository.upsert(
            ResourceState(
              markedResource = it,
              statuses = mutableListOf(Status(event.name, clock.instant().toEpochMilli()))
            )
          )
        }
      }
    }
  }
}
