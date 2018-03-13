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

import com.netflix.spinnaker.SwabbieAgent
import com.netflix.spinnaker.swabbie.LockManager
import com.netflix.spinnaker.swabbie.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.ResourceHandler
import com.netflix.spinnaker.swabbie.model.Work
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executor

@Component
@ConditionalOnExpression("\${swabbie.agents.clean.enabled}")
class ResourceCleanerAgent(
  private val executor: Executor,
  private val lockManager: LockManager,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val work: List<Work>,
  private val resourceHandlers: List<ResourceHandler<*>>,
  private val discoverySupport: DiscoverySupport
): SwabbieAgent {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  @Scheduled(fixedDelayString = "\${swabbie.agents.clean.intervalSeconds:3600000}")
  override fun execute() {
    discoverySupport.ifUP {
      try {
        log.info("Resource cleaners started...")
        resourceTrackingRepository.getMarkedResourcesToDelete()
          ?.filter { it.notificationInfo.notificationStamp != null && it.adjustedDeletionStamp != null }
          ?.forEach {
            it.takeIf {
              lockManager.acquire(locksName(PREFIX, it.namespace), lockTtlSeconds = 3600)
            }?.let { markedResource ->
                resourceHandlers.find {
                  handler -> handler.handles(markedResource.resourceType, markedResource.cloudProvider)
                }.let { handler ->
                    if (handler == null) {
                      throw IllegalStateException("No Suitable handler found for $markedResource")
                    } else {
                      work.find { it.namespace == markedResource.namespace }?.let { w ->
                        executor.execute {
                          handler.clean(markedResource, w.configuration, {
                            lockManager.release(locksName(PREFIX, it.namespace))
                          })

                        }
                      }
                    }
                  }
              }
          }
      } catch (e: Exception) {
        log.error("Failed to execute resource cleaners", e)
      }
    }
  }

  private val PREFIX = "{swabbie:mark}"
}
