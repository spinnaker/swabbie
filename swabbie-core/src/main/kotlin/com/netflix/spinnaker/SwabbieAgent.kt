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

package com.netflix.spinnaker

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.DiscoverySupport
import com.netflix.spinnaker.swabbie.AgentRunner
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import rx.Scheduler
import rx.schedulers.Schedulers
import java.time.Clock
import java.time.Duration
import java.time.temporal.Temporal
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

interface SwabbieAgent : ApplicationListener<ApplicationReadyEvent> {
  fun process(workConfiguration: WorkConfiguration, onCompleteCallback: () -> Unit)
  fun initialize()
  fun finalize(workConfiguration: WorkConfiguration)
}

abstract class ScheduledAgent(
  private val clock: Clock,
  val registry: Registry,
  private val agentRunner: AgentRunner,
  private val discoverySupport: DiscoverySupport
) : SwabbieAgent {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  protected val failedAgentId: Id = registry.createId("swabbie.agents.failed")
  private val lastRunAgeId: Id = registry.createId("swabbie.agents.${javaClass.simpleName.toLowerCase()}.run.age")
  private val worker: Scheduler.Worker = Schedulers.io().createWorker()

  override fun onApplicationEvent(event: ApplicationReadyEvent?) {
    worker.schedulePeriodically({
      discoverySupport.ifUP {
        agentRunner.run(this)
      }
    }, 0, getAgentFrequency(), TimeUnit.SECONDS)
  }

  override fun finalize(workConfiguration: WorkConfiguration) {
    registry.gauge(
      lastRunAgeId.withTags(
        "configuration", workConfiguration.namespace,
        "resourceType", workConfiguration.resourceType,
        "provider", workConfiguration.cloudProvider
      ), this, {
      Duration.between(it.getLastAgentRun(), clock.instant()).toMillis().toDouble()
    })
  }

  @PostConstruct
  private fun init() {
    log.info("Initializing agent ${javaClass.simpleName}")
  }

  abstract fun getLastAgentRun(): Temporal?
  abstract fun getAgentFrequency(): Long
}
