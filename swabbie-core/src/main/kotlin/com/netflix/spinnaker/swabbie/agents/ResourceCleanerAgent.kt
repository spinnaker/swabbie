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

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.ScheduledAgent
import com.netflix.spinnaker.swabbie.AgentRunner
import com.netflix.spinnaker.swabbie.DiscoverySupport
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.Temporal
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches resources scheduled for deletion and deletes them
 */
@Component
@ConditionalOnExpression("\${swabbie.agents.clean.enabled}")
class ResourceCleanerAgent(
  registry: Registry,
  agentRunner: AgentRunner,
  discoverySupport: DiscoverySupport,
  private val clock: Clock,
  private val executor: AgentExecutor,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>
) : ScheduledAgent(clock, registry, agentRunner, discoverySupport) {
  @Value("\${swabbie.agents.clean.intervalSeconds:3600}")
  private var interval: Long = 3600

  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())
  private val lastCleanerAgentRun: Instant
    get() = _lastAgentRun.get()

  override fun getLastAgentRun(): Temporal? = lastCleanerAgentRun
  override fun getAgentFrequency(): Long = interval
  override fun initialize() {
    _lastAgentRun.set(clock.instant())
  }

  override fun process(workConfiguration: WorkConfiguration, onCompleteCallback: () -> Unit) {
    try {
      resourceTrackingRepository.getMarkedResourcesToDelete()
        ?.filter { it.namespace.equals(workConfiguration.namespace, ignoreCase = true) && it.notificationInfo.notificationStamp != null && it.adjustedDeletionStamp != null }
        ?.forEach { markedResource ->
          resourceTypeHandlers.find { handler ->
            handler.handles(workConfiguration)
          }.let { handler ->
              if (handler == null) {
                throw IllegalStateException("No Suitable handler found for $markedResource")
              } else {
                executor.execute {
                  handler.clean(markedResource, workConfiguration, onCompleteCallback)
                }
              }
            }
        }
    } catch (e: Exception) {
      registry.counter(failedAgentId.withTags("agentName", this.javaClass.simpleName, "configuration", workConfiguration.namespace)).increment()
      log.error("Failed to run resource cleaners", e)
    }
  }
}
