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

import com.netflix.spinnaker.swabbie.LockManager
import com.netflix.spinnaker.swabbie.ResourceHandler
import com.netflix.spinnaker.swabbie.model.Work
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.ScheduledAgent
import com.netflix.spinnaker.swabbie.DiscoverySupport
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.beans.factory.annotation.Value
import java.time.Clock
import java.time.Instant
import java.time.temporal.Temporal
import java.util.concurrent.atomic.AtomicReference

/**
 * Searches for clean up candidates and marks them for deletion in the near future
 */
@Component
@ConditionalOnExpression("\${swabbie.agents.mark.enabled}")
class ResourceMarkerAgent(
  clock: Clock,
  discoverySupport: DiscoverySupport,
  registry: Registry,
  private val executor: AgentExecutor,
  private val lockManager: LockManager,
  private val work: List<Work>,
  private val resourceHandlers: List<ResourceHandler<*>>
): ScheduledAgent(clock, registry, discoverySupport) {
  @Value("\${swabbie.agents.mark.intervalSeconds:3600000}")
  private var interval: Long = 3600000

  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())
  private val lastMarkerAgentRun: Instant
    get() = _lastAgentRun.get()

  override val agentName: String
    get() = "MARKER"

  override fun getLastAgentRun(): Temporal? = lastMarkerAgentRun
  override fun getAgentFrequency(): Long = interval
  override fun setLastAgentRun(instant: Instant) {
    _lastAgentRun.set(instant)
  }

  override fun initializeAgent() {
    log.info("Marker agent starting with {}", work)
  }

  override fun run() {
    var currentConfiguration: WorkConfiguration? = null
    try {
      work.forEach {
        currentConfiguration = it.configuration
        it.takeIf {
          lockManager.acquire(locksName(it.namespace), lockTtlSeconds = 3600)
        }?.let { w ->
            resourceHandlers.find { handler ->
              handler.handles(w.configuration.resourceType, w.configuration.cloudProvider)
            }.let { handler ->
                if (handler == null) {
                  throw IllegalStateException(
                    String.format("No Suitable handler found for %s", w)
                  )
                } else {
                  executor.execute {
                    handler.mark(w.configuration, {
                      lockManager.release(locksName(w.namespace))
                    })
                  }
                }
              }
          }
      }
    } catch (e: Exception) {
      registry.counter(failedAgentId.withTags("agentName", agentName, "configuration", currentConfiguration?.namespace ?: "unknown")).increment()
      log.error("Failed to run resource marker agent", e)
    }
  }
}
