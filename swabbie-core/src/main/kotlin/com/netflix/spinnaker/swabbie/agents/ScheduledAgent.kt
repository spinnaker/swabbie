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
import com.netflix.spinnaker.config.Schedule
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import com.netflix.spinnaker.swabbie.MetricsSupport
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Scheduler
import rx.schedulers.Schedulers
import java.time.*
import java.time.temporal.Temporal
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

abstract class ScheduledAgent(
  private val clock: Clock,
  val registry: Registry,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>,
  private val workConfigurations: List<WorkConfiguration>,
  private val agentExecutor: Executor,
  private val swabbieProperties: SwabbieProperties
) : SwabbieAgent, MetricsSupport(registry) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val worker: Scheduler.Worker = Schedulers.io().createWorker()

  override val onDiscoveryUpCallback: (event: RemoteStatusChangedEvent) -> Unit
    get() = {
      worker.schedulePeriodically({
        when {
          LocalDateTime.now(clock).dayOfWeek in swabbieProperties.schedule.getResolvedDays() ->
            when {
              timeToWork(swabbieProperties.schedule, clock) -> {
                log.info("Swabbie time to work!")
                runSwabbie()
              }
              else -> log.info("Swabbie off hours!")
            }
          else -> log.info("Swabbie takes a day off on {}.!", LocalDateTime.now(clock).dayOfWeek)
        }

      }, getAgentDelay(), getAgentFrequency(), TimeUnit.SECONDS)
    }

  override val onDiscoveryDownCallback: (event: RemoteStatusChangedEvent) -> Unit
    get() = { stop() }

  override fun finalize(workConfiguration: WorkConfiguration) {
    log.info("Completed run for agent {} with configuration {}", javaClass.simpleName, workConfiguration)
  }

  @PostConstruct
  private fun init() {
    log.info("Initializing agent ${javaClass.simpleName}")
    registry.gauge(lastRunAgeId.withTag("agentName", javaClass.simpleName), this) {
      Duration
        .between(it.getLastAgentRun(), clock.instant())
        .toMillis().toDouble()
    }
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

  @PreDestroy
  private fun stop() {
    log.info("Stopping agent ${javaClass.simpleName}")
    if (!worker.isUnsubscribed) {
      worker.unsubscribe()
    }
  }

  override fun process(workConfiguration: WorkConfiguration, onCompleteCallback: () -> Unit) {
    val action = getAction()
    try {
      val handlerAction: (handler: ResourceTypeHandler<*>) -> Unit = {
        when(action) {
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

  companion object {
    fun timeToWork(schedule: Schedule, clock: Clock): Boolean {
      if (!schedule.enabled) {
        return true
      }

      val startTime: LocalTime = schedule.getResolvedStartTime()
      val endTime: LocalTime = schedule.getResolvedEndTime()
      val now = LocalTime.from(clock.instant().atZone(schedule.getZoneId()))

      return if (startTime <= endTime) {
        // Work during the day
        now.isAfter(startTime) && now.isBefore(endTime)
      } else {
        // Work during the night
        now.isAfter(startTime) || now.isBefore(endTime)
      }
    }
  }

  abstract fun getLastAgentRun(): Temporal?
  abstract fun getAgentFrequency(): Long
  abstract fun getAgentDelay(): Long
  abstract fun getAction(): Action
}
