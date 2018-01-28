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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executor

@Component
@ConditionalOnExpression("\${swabbie.clean.enabled}")
class ResourceCleanerAgent(
  private val executor: Executor,
  private val lockManager: LockManager,
  private val resourceRepository: ResourceRepository,
  private val resourceHandlers: List<ResourceHandler>,
  @Autowired(required = false) private val discoveryClient: DiscoveryClient?
): DiscoverySupport(discoveryClient = discoveryClient) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  @Scheduled(fixedDelayString = "\${swabbie.clean.intervalSeconds:3600}")
  fun execute() {
    if (enabled()) {
      try {
        log.info("Resource cleaners started...")
        resourceRepository.getMarkedResourcesToDelete()
          ?.forEach {
            it.takeIf { lockManager.acquireLock("clean:${it.configurationId}", lockTtlSeconds = 3600) }
              ?.also { markedResource ->
                resourceHandlers.find {
                  handler -> handler.handles(markedResource.resourceType, markedResource.cloudProvider)
                }.let { handler ->
                  if (handler == null) {
                    throw IllegalStateException(
                      String.format("No Suitable handler found for %s", markedResource)
                    )
                  } else {
                    executor.execute {
                      handler.clean(markedResource)
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
