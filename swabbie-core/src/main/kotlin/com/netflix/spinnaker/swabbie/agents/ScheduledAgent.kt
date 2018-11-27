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
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import com.netflix.spinnaker.swabbie.CacheStatus
import com.netflix.spinnaker.swabbie.MetricsSupport
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.*
import java.time.temporal.Temporal
import java.util.concurrent.*
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

abstract class ScheduledAgent(
  private val clock: Clock,
  val registry: Registry,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>,
  private val workConfigurations: List<WorkConfiguration>,
  private val agentExecutor: Executor,
  private val swabbieProperties: SwabbieProperties,
  private val cacheStatus: CacheStatus,
  private val dynamicConfigService: DynamicConfigService
) : SwabbieAgent, MetricsSupport(registry) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val executorService = Executors.newSingleThreadScheduledExecutor()
  var runningTask: ScheduledFuture<*>? = null

  override val onDiscoveryUpCallback: (event: RemoteStatusChangedEvent) -> Unit
    get() = { waitForCacheThenStart() }

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

  private fun waitForCacheThenStart() {
    executorService.schedule({
      try {
        while (!cacheStatus.cachesLoaded()) {
          log.debug("Can't work until the caches load...")
          Thread.sleep(Duration.ofSeconds(5).toMillis())
        }
        log.debug("Caches loaded.")
        scheduleSwabbie()
      } catch (e: Exception) {
        log.error("Failed while waiting for cache to start in ${javaClass.simpleName}.", e)
      }
    }, 15, TimeUnit.SECONDS)
  }

  private fun scheduleSwabbie() {
    if (dynamicConfigService.isEnabled("swabbie.work", true)) {
      runningTask = executorService.scheduleWithFixedDelay({
        try {
          when {
            LocalDateTime.now(clock).dayOfWeek in swabbieProperties.schedule.getResolvedDays() ->
              when {
                timeToWork(swabbieProperties.schedule, clock) -> {
                  log.info("Swabbie schedule: working")
                  runSwabbie()
                }
                else -> log.info("Swabbie schedule: off hours")
              }
            else -> log.info("Swabbie schedule: off hours on {}", LocalDateTime.now(clock).dayOfWeek)
          }
        } catch (e: Exception) {
          registry.counter(
              failedDuringSchedule.withTags(
                  "agentName", this.javaClass.simpleName
              )).increment()
          log.error("Failed during schedule method for {}", javaClass.simpleName)
        }
      }, getAgentDelay(), getAgentFrequency(), TimeUnit.SECONDS)
    } else {
      log.info("Swabbie schedule: disabled via property swabbie.work")
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
    runningTask?.cancel(true)
  }

  override fun process(workConfiguration: WorkConfiguration, onCompleteCallback: () -> Unit) {
    val action = getAction()
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
        try {
          handlerAction.invoke(handler)
        } catch (e: Exception) {
          registry.counter(
            failedAgentId.withTags(
              "agentName", this.javaClass.simpleName,
              "configuration", workConfiguration.namespace,
              "action", action.name
            )).increment()
          log.error("Failed to run {} {} for {}", javaClass.simpleName, action, workConfiguration.namespace, e)
        }
      }
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
