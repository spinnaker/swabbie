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
import com.netflix.spinnaker.swabbie.ScheduledAgent
import com.netflix.spinnaker.swabbie.DiscoverySupport
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.WorkConfigurator
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
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
  registry: Registry,
  discoverySupport: DiscoverySupport,
  executor: AgentExecutor,
  resourceTypeHandlers: List<ResourceTypeHandler<*>>,
  workConfigurator: WorkConfigurator,
  private val clock: Clock
) : ScheduledAgent(clock, registry, executor, discoverySupport, resourceTypeHandlers, workConfigurator) {
  @Value("\${swabbie.agents.mark.intervalSeconds:3600}")
  private var interval: Long = 3600

  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())
  private val lastMarkerAgentRun: Instant
    get() = _lastAgentRun.get()

  override fun getLastAgentRun(): Temporal? = lastMarkerAgentRun
  override fun getAgentFrequency(): Long = interval
  override fun initialize() {
    _lastAgentRun.set(clock.instant())
  }

  override fun process(workConfiguration: WorkConfiguration, onCompleteCallback: () -> Unit) {
    processForAction(Action.MARK, workConfiguration, onCompleteCallback)
  }
}
