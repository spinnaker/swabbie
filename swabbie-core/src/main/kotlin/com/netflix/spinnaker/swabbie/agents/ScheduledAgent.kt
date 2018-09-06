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
import com.netflix.spinnaker.swabbie.DiscoverySupport
import com.netflix.spinnaker.swabbie.MetricsSupport
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import rx.Scheduler
import rx.schedulers.Schedulers
import java.time.*
import java.time.temporal.Temporal
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

abstract class ScheduledAgent(
  private val clock: Clock,
  val registry: Registry,
  private val discoverySupport: DiscoverySupport,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>,
  private val workConfigurations: List<WorkConfiguration>,
  private val agentExecutor: Executor
) : SwabbieAgent, MetricsSupport(registry) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val worker: Scheduler.Worker = Schedulers.io().createWorker()
  private val swabbieStartTime = LocalTime.parse("09:00:00")
  private val swabbieStopTime = LocalTime.parse("15:00:00")

  override fun onApplicationEvent(event: ApplicationReadyEvent?) {
    worker.schedulePeriodically({
      discoverySupport.ifUP {
        when {
          LocalDateTime.now(clock).dayOfWeek !in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) ->
            when {
              LocalTime.now(clock).isAfter(swabbieStartTime) && LocalTime.now(clock).isBefore(swabbieStopTime) -> {
                log.debug("Swabbie time to work!")
                runSwabbie()
              }
              else -> log.debug("Swabbie off hours!!")

            }
          else -> log.debug("Swabbie takes a day off on {}.!", LocalDateTime.now(clock).dayOfWeek)
        }
      }
    }, getAgentDelay(), getAgentFrequency(), TimeUnit.SECONDS)
  }

  private fun runSwabbie() {
    initialize()
    workConfigurations.forEach { workConfiguration ->
      log.info("{} running with configuration {}", this.javaClass.simpleName, workConfiguration)
      process(
        workConfiguration = workConfiguration,
        onCompleteCallback = {
          finalize(workConfiguration)
        }
      )
    }
  }

  override fun finalize(workConfiguration: WorkConfiguration) {
    log.info("Completed run for agent {} with configuration {}", javaClass.simpleName, workConfiguration)
  }

  @PostConstruct
  private fun init() {
    log.info("Initializing agent ${javaClass.simpleName}")
    registry.gauge(lastRunAgeId.withTag("agentName", javaClass.simpleName), this, {
      Duration
        .between(it.getLastAgentRun(), clock.instant())
        .toMillis().toDouble()
    })
  }

  override fun process(workConfiguration: WorkConfiguration, onCompleteCallback: () -> Unit) {
    val action = getAction()
    try {
      val handlerAction: (handler: ResourceTypeHandler<*>) -> Unit = {
        when (action) {
          Action.MARK -> it.mark(workConfiguration, onCompleteCallback)
          Action.NOTIFY -> it.notify(workConfiguration, onCompleteCallback)
          Action.DELETE -> it.delete(workConfiguration, onCompleteCallback)
          else -> log.warn("Unknown action {}", action.name)
        }
      }

      resourceTypeHandlers.find { handler ->
        handler.handles(workConfiguration)
      }?.let { handler ->
        agentExecutor.execute {
          handlerAction.invoke(handler)
        }
      }
    } catch (e: Exception) {
      registry.counter(
        failedAgentId.withTags(
          "agentName", this.javaClass.simpleName,
          "configuration", workConfiguration.namespace,
          "action", action.name
        )).increment()
      log.error("Failed to run agent {}", javaClass.simpleName, e)
    }
  }

  abstract fun getLastAgentRun(): Temporal?
  abstract fun getAgentFrequency(): Long
  abstract fun getAgentDelay(): Long
  abstract fun getAction(): Action
}
