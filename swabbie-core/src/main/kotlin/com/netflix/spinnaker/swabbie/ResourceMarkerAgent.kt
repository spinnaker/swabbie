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
import com.netflix.spinnaker.swabbie.model.Configurations
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executor

@Component
@ConditionalOnExpression("\${swabbie.mark.enabled}")
class ResourceMarkerAgent(
  private val executor: Executor,
  private val lockManager: LockManager,
  private val configurations: Configurations<String, WorkConfiguration>,
  private val resourceHandlers: List<ResourceHandler>,
  @Autowired(required = false) private val discoveryClient: DiscoveryClient?
): DiscoverySupport(discoveryClient = discoveryClient) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  @Scheduled(fixedDelayString = "\${swabbie.mark.intervalSeconds:3600}")
  fun execute() {
    if (enabled()) {
      try {
        log.info("Resource markers started...")
        configurations.keys.forEach { configurationId ->
          configurationId.takeIf { lockManager.acquireLock("mark:$configurationId", lockTtlSeconds = 3600) }
            ?.also {
              configurations[it]?.let { work ->
                resourceHandlers.find { handler ->
                  handler.handles(work.resourceType, work.cloudProvider)
                }.let { handler ->
                  if (handler == null) {
                    throw IllegalStateException(
                      String.format("No Suitable handler found for %s", work)
                    )
                  } else {
                    executor.execute {
                      handler.mark(work)
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
