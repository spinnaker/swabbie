/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.work

import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.patterns.PolledMeter
import com.netflix.spinnaker.config.Schedule
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.discovery.DiscoveryStatusListener
import com.netflix.spinnaker.kork.discovery.RemoteStatusChangedEvent
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.CacheStatus
import java.lang.IllegalStateException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Serves as the producer of work for resource handlers, monitors and refills the work queue once all work is complete
 * @property WorkQueue the queue to contain work for processing by resource handlers
 */
@Component
class WorkQueueManager(
  private val dynamicConfigService: DynamicConfigService,
  private val swabbieProperties: SwabbieProperties,
  private val queue: WorkQueue,
  private val clock: Clock,
  private val registry: Registry,
  private val cacheStatus: CacheStatus,
  private val discoveryStatusListener: DiscoveryStatusListener
) : ApplicationListener<RemoteStatusChangedEvent> {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val queueId = registry.createId("swabbie.work.queue")
  private val queueSizeId = registry.createId("swabbie.work.queueSize")

  init {
    PolledMeter.using(registry)
      .withId(queueSizeId)
      .monitorValue(queue.size())
  }

  override fun onApplicationEvent(event: RemoteStatusChangedEvent) {
    if (event.source.isUp) {
      ensureLoadedCaches()
      queue.refillOnEmpty()
    }
  }

  /**
   * Monitors and refills the [WorkQueue] if empty.
   * The queue is emptied if swabbie is disabled via a persisted property/config or outside the configured schedule
   */
  @Scheduled(fixedDelayString = "\${swabbie.queue.monitor-interval-ms:900000}")
  fun monitor() {
    if (!discoveryStatusListener.isEnabled) {
      // do nothing, we're down in discovery and we want active instances to control the queue
      return
    }

    when (isEnabled()) {
      true -> queue.refillOnEmpty()
      false -> queue.clear()
    }
  }

  /**
   * Swabbie is considered disabled if it is out of service in discovery, disabled via fast property, or the
   *  schedule determines it is off hours.
   */
  internal fun isEnabled(): Boolean {
    if (dynamicConfigService.isEnabled(SWABBIE_FLAG_PROPERY, false)) {
      log.info("Swabbie schedule: disabled via property $SWABBIE_FLAG_PROPERY")
      return false
    }

    if (!timeToWork(swabbieProperties.schedule, clock)) {
      log.warn("Swabbie schedule: off hours on {}", LocalDateTime.now(clock).dayOfWeek)
      return false
    }

    log.info("Swabbie schedule: time to work")
    return true
  }

  /**
   * Seeds the [WorkQueue] with work items [com.netflix.spinnaker.swabbie.model.WorkItem]
   * @see [com.netflix.spinnaker.swabbie.model.WorkConfiguration.toWorkItems]
   */
  private fun WorkQueue.refillOnEmpty() {
    if (!isEmpty()) {
      return
    }

    try {
      seed()
      registry.counter(queueId.withTag("filled", "success")).increment()
    } catch (e: Exception) {
      registry.counter(queueId.withTag("filled", "failure")).increment()
    }
  }

  private fun ensureLoadedCaches() {
    try {
      while (!cacheStatus.cachesLoaded()) {
        log.debug("Can't work until the caches load...")
        Thread.sleep(Duration.ofSeconds(5).toMillis())
      }
      log.debug("Caches loaded.")
    } catch (e: Exception) {
      log.error("Failed while waiting for cache to start", e)
    }
  }

  companion object {
    fun timeToWork(schedule: Schedule, clock: Clock): Boolean {
      if (!schedule.enabled) {
        return true
      }

      val startTime: LocalTime = schedule.getResolvedStartTime()
      val endTime: LocalTime = schedule.getResolvedEndTime()
      val now: LocalTime = LocalTime.from(clock.instant().atZone(schedule.getZoneId()))
      if (startTime.isAfter(endTime)) {
        throw IllegalStateException("Scheduled startTime: $startTime cannot be after endTime: $endTime")
      }

      return LocalDate.now(clock).dayOfWeek in schedule.allowedDaysOfWeek && now.isAfter(startTime) && now.isBefore(endTime)
    }
  }
}

const val SWABBIE_FLAG_PROPERY = "swabbie.work"
