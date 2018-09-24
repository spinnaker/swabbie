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

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.MetricsSupport
import com.netflix.spinnaker.swabbie.repositories.ResourceTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Handles any further change in state after a resource is updated
 * for resources that are changed via an orca task.
 */
@Component
class ResourceTrackingManager(
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val clock: Clock,
  private val registry: Registry
) : MetricsSupport(registry) {

  private val log: Logger = LoggerFactory.getLogger(javaClass)


  /**
   * Other event types are handled in {@link AbstractResourceTypeHandler}.
   * These events are emitted after an orca task is completed in
   * {@link OrcaTaskMonitoringAgent}
   */
  @EventListener
  fun handleEvents(event: Event) {
    when (event) {
      is SoftDeleteResourceEvent -> {
        log.info("Soft deleted {}. Configuration: {}", event.markedResource.uniqueId(), event.workConfiguration)
        resourceTrackingRepository.setSoftDeleted(event.markedResource)
      }

      is DeleteResourceEvent -> {
        log.info("Deleted {}. Configuration: {}", event.markedResource.uniqueId(), event.workConfiguration)
        resourceTrackingRepository.remove(event.markedResource)
      }
    }
  }
}
