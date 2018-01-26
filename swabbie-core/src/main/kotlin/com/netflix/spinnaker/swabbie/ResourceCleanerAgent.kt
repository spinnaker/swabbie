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

package com.netflix.spinnaker.swabbie

import com.netflix.discovery.DiscoveryClient
import com.netflix.spinnaker.swabbie.handlers.ResourceHandler
import com.netflix.spinnaker.swabbie.model.MarkedResource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executor
import java.time.ZoneId
import java.time.LocalDate

@Component
class ResourceCleanerAgent(
  private val executor: Executor,
  private val resourceRepository: ResourceRepository,
  @Autowired(required = false) private val discoveryClient: DiscoveryClient?,
  private val resourceHandlers: List<ResourceHandler>
): DiscoverySupport(discoveryClient = discoveryClient) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  @Scheduled(fixedDelayString = "\${resource.cleaners.interval:120000}")
  fun execute() {
    if (enabled()) {
      try {
        log.info("Swabbie cleaners started...")
        val resourcesToDelete: List<MarkedResource>? = resourceRepository.getMarkedResourcesToDelete()
        resourcesToDelete?.forEach {trackedResource ->
          resourceHandlers.find { handler -> handler.handles(trackedResource.resourceType, trackedResource.cloudProvider) }.let { handler ->
            if (handler == null) {
              throw IllegalStateException(
                String.format("No Suitable handler found for %s", trackedResource)
              )
            } else {
              executor.execute {
                Instant.ofEpochMilli(trackedResource.projectedTerminationTime)
                  .atZone(ZoneId.systemDefault())
                  .toLocalDate().let { terminationDate ->
                    if (terminationDate.isAfter(LocalDate.now())) {
                      handler.cleanup(trackedResource)
                    }
                  }
              }
            }
          }
        }
      } catch (e: Exception) {
        log.error("failed", e)
      }
    }
  }
}
