/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.orca

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.discovery.RemoteStatusChangedEvent
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.events.OrcaTaskFailureEvent
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.Temporal
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

@Component
class OrcaTaskMonitoringAgent(
  private val clock: Clock,
  val registry: Registry,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val orcaService: OrcaService,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val retrySupport: RetrySupport,
  private val lockingService: Optional<LockingService>
) : ApplicationListener<RemoteStatusChangedEvent> {

  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val executorService = Executors.newSingleThreadScheduledExecutor()
  private val lastRunAgeId: Id = registry.createId("swabbie.agents.run.age")

  private val timeoutMillis: Long = 5000
  private val maxAttempts: Int = 3

  override fun onApplicationEvent(event: RemoteStatusChangedEvent) {
    if (event.source.isUp) {
      executorService.scheduleWithFixedDelay(
        {
          withLocking(javaClass.simpleName) { monitorOrcaTasks() }
        },
        getAgentDelay(), getAgentFrequency(), TimeUnit.SECONDS
      )
    } else {
      stop()
    }
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

  @PreDestroy
  private fun stop() {
    log.info("Stopping agent ${javaClass.simpleName}")
    executorService.shutdown()
  }

  private fun withLocking(
    agentName: String,
    callback: () -> Unit
  ) {
    if (lockingService.isPresent) {
      val normalizedLockName = (agentName)
        .replace(":", ".")
        .toLowerCase()
      val lockOptions = LockManager.LockOptions()
        .withLockName(normalizedLockName)
        .withMaximumLockDuration(lockingService.get().swabbieMaxLockDuration)
      lockingService.get().acquireLock(lockOptions) {
        callback.invoke()
      }
    } else {
      log.warn("***Locking not ENABLED, continuing without locking for ${javaClass.simpleName}***")
      callback.invoke()
    }
  }

  private fun monitorOrcaTasks() {
    initialize()

    val inProgressTasks = taskTrackingRepository.getInProgress()

    inProgressTasks
      .forEach { taskId ->
        val response = getTask(taskId)
        if (response.status.isComplete()) {
          val taskInfo = taskTrackingRepository.getTaskDetail(taskId)
          if (taskInfo == null) {
            log.error(
              "TaskDetail not found in tracking repository for {}. Unable to fire completion event for task.",
              kv("taskId", taskId)
            )
            return
          }

          when {
            response.status.isSuccess() -> {
              log.debug(
                "Orca task {} succeeded. Task complete info: {}",
                kv("taskId", taskId),
                taskInfo
              )
              taskTrackingRepository.setSucceeded(taskId)
            }
            response.status.isFailure() -> {
              log.error(
                "Orca task {} for action {} did not complete. Status: {}. Resources: {}",
                kv("taskId", taskId),
                taskInfo.action,
                kv("responseStatus", response.status),
                taskInfo.markedResources.map { it.uniqueId() }
              )
              taskTrackingRepository.setFailed(taskId)
              taskInfo.markedResources
                .forEach { markedResource ->
                  applicationEventPublisher.publishEvent(
                    OrcaTaskFailureEvent(taskInfo.action, markedResource, taskInfo.workConfiguration)
                  )
                }
            }
            response.status.isIncomplete() -> {
              log.debug("Still monitoring orca task {}", kv("taskId", taskId))
            }
          }
        }
      }

    clean()
  }

  private fun getTask(taskId: String): TaskDetailResponse =
    retrySupport.retry(
      {
        orcaService.getTask(taskId)
      },
      maxAttempts, timeoutMillis, false
    )

  private fun clean() {
    taskTrackingRepository.cleanUpFinishedTasks(daysToKeepTasks)
  }

  @Value("\${swabbie.agents.orca-task-monitor.interval-seconds:60}")
  private var interval: Long = 60

  @Value("\${swabbie.agents.orca-task-monitor.delay-seconds:60}")
  private var delay: Long = 0

  @Value("\${swabbie.agents.orca-task-monitor.days-to-keep-tasks:2}")
  private var daysToKeepTasks: Int = 2

  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())

  private val lastOrcaTaskMonitorAgentRun: Instant
    get() = _lastAgentRun.get()

  fun getLastAgentRun(): Temporal? = lastOrcaTaskMonitorAgentRun
  fun getAgentFrequency(): Long = interval
  fun getAgentDelay(): Long = delay
  fun initialize() {
    _lastAgentRun.set(clock.instant())
  }
}
