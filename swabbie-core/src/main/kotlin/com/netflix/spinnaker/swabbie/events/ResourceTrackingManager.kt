/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.events

import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Handles any further change in state after a resource is updated
 * for resources that are changed via an orca task.
 */
// TODO: (jeyrs) - Review me. Don't think this class is needed since the resource removal happens on site
@Component
class ResourceTrackingManager(
  private val resourceTrackingRepository: ResourceTrackingRepository
) {

  private val log: Logger = LoggerFactory.getLogger(javaClass)

  /**
   * Other event types are handled in [AbstractResourceTypeHandler].
   * These events are emitted after an orca task is completed in
   * [OrcaTaskMonitoringAgent]
   */
  @EventListener
  fun handleEvents(event: Event) {
    when (event) {
      is DeleteResourceEvent -> {
        log.debug("Deleted {}. Configuration: {}", event.markedResource.uniqueId(), event.workConfiguration)
        resourceTrackingRepository.remove(event.markedResource)
      }
    }
  }
}
