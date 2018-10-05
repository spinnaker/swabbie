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

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.MetricsSupport
import com.netflix.spinnaker.swabbie.repositories.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repositories.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.discovery.DiscoveryActivated
import com.netflix.spinnaker.swabbie.events.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import rx.Scheduler
import rx.schedulers.Schedulers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.Temporal
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Component
class OrcaTaskMonitoringAgent (
  private val clock: Clock,
  val registry: Registry,
  private val swabbieProperties: SwabbieProperties,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val orcaService: OrcaService,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val retrySupport: RetrySupport,
  private val lockingService: Optional<LockingService>
) : DiscoveryActivated, MetricsSupport(registry) {

  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val worker: Scheduler.Worker = Schedulers.io().createWorker()

  private val timeoutMillis: Long = 5000
  private val maxAttempts: Int = 3

  override val onDiscoveryUpCallback: (event: RemoteStatusChangedEvent) -> Unit
    get() = {
      worker.schedulePeriodically({
       withLocking(javaClass.simpleName) { monitorOrcaTasks() }
      }, getAgentDelay(), getAgentFrequency(), TimeUnit.SECONDS)
    }

  override val onDiscoveryDownCallback: (event: RemoteStatusChangedEvent) -> Unit
    get() = { stop() }

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
    if (!worker.isUnsubscribed) {
      worker.unsubscribe()
    }
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
      log.warn("***Locking not ENABLED***")
      callback.invoke()
    }
  }

  private fun monitorOrcaTasks() {
    initialize()

    val inProgressTasks = taskTrackingRepository.getInProgress()
    if (inProgressTasks.isEmpty()) {
      log.debug("No active orca tasks to monitor from ${javaClass.simpleName}.")
    }

    inProgressTasks
      .forEach { taskId ->
        val response = getTask(taskId)
        if (response.status.isComplete()) {
          val taskInfo = taskTrackingRepository.getTaskDetail(taskId)
          if (taskInfo == null) {
            log.error("Detail for task $taskId was not found in repository. Something went wrong!")
            return
          }

          when {
            response.status.isSuccess() -> {
              log.debug("Orca task $taskId succeeded. Task complete info: $taskInfo")
              publishEvent(taskInfo)
              taskTrackingRepository.setSucceeded(taskId)
            }
            response.status.isFailure() -> {
              log.error("Orca task $taskId did not complete. Status: ${response.status}. Task complete info: $taskInfo")
              taskTrackingRepository.setFailed(taskId)
              taskInfo.markedResources
                .forEach { markedResource ->
                  applicationEventPublisher.publishEvent(OrcaTaskFailureEvent(taskInfo.action, markedResource, taskInfo.workConfiguration))
                }
            }
            response.status.isIncomplete() -> {
              log.debug("Still monitoring orca task $taskId.")
            }
          }
        }
      }

    clean()
  }

  private fun getTask(taskId: String): TaskDetailResponse =
    retrySupport.retry({
      orcaService.getTask(taskId)
    }, maxAttempts, timeoutMillis, false)

  private fun clean() {
    taskTrackingRepository.cleanUpFinishedTasks(daysToKeepTasks)
  }

  private fun publishEvent(taskInfo: TaskCompleteEventInfo) {
    when (taskInfo.action) {
      Action.SOFTDELETE -> {
        taskInfo.markedResources
          .forEach { markedResource ->
            applicationEventPublisher.publishEvent(SoftDeleteResourceEvent(markedResource, taskInfo.workConfiguration))
          }
      }
      Action.DELETE -> {
        taskInfo.markedResources
          .forEach { markedResource ->
            applicationEventPublisher.publishEvent(DeleteResourceEvent(markedResource, taskInfo.workConfiguration))
          }
      }
      Action.RESTORE -> {
        taskInfo.markedResources
          .forEach { markedResource ->
            applicationEventPublisher.publishEvent(RestoreResourceEvent(markedResource, taskInfo.workConfiguration))
            // we also want to opt this resource out in case it was accidentally deleted.
            applicationEventPublisher.publishEvent(OptOutResourceEvent(markedResource, taskInfo.workConfiguration))
          }
      }
      else -> {
        TODO("Not implemented: event publishing not implemented for action ${taskInfo.action}")
      }
    }
  }

  @Value("\${swabbie.agents.orcaTaskMonitor.intervalSeconds:10}")
  private var interval: Long = 10

  @Value("\${swabbie.agents.orcaTaskMonitor.delaySeconds:30}")
  private var delay: Long = 0

  @Value("\${swabbie.agents.orcaTaskMonitor.daysToKeepTasks:2}")
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
