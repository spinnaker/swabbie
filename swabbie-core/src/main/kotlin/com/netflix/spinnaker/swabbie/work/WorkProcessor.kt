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

package com.netflix.spinnaker.swabbie.work

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.discovery.DiscoveryStatusListener
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.swabbie.CacheStatus
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.WorkItem
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression("\${swabbie.enabled:true}")
class WorkProcessor(
  val clock: Clock,
  val registry: Registry,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>,
  private val workQueue: WorkQueue,
  private val lockingService: LockingService,
  private val cacheStatus: CacheStatus,
  private val discoveryStatusListener: DiscoveryStatusListener
) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val workId = registry.createId("swabbie.work")
  private val workDurationId = registry.createId("swabbie.resources.work.duration")

  /**
   * Takes work [WorkItem] off the [WorkQueue], acquires a lock and
   * dispatches the corresponding work configuration to a suitable handler for processing
   */
  @Scheduled(fixedDelayString = "\${swabbie.work.interval-ms:180000}")
  fun process() {
    if (!discoveryStatusListener.isEnabled) {
      // queue processors shouldn't work while they're down in discovery
      return
    }
    if (!cacheStatus.cachesLoaded()) {
      log.warn("Caches not fully loaded yet. Skipping")
      return
    }

    withLocking {
      do {
        try {
          val work = workQueue.pop()
          if (work == null) {
            log.debug("No Work to do. Skipping...")
          } else {
            process(work)
          }
        } catch (e: Exception) {
          log.error("Error while processing work", e)
        }
      } while (!workQueue.isEmpty())
    }
  }

  private fun process(work: WorkItem) {
    val elapsedTimeMillis = measureTimeMillis {
      try {
        resourceTypeHandlers.find {
          it.handles(work.workConfiguration)
        }?.let { handler ->
          log.debug("${handler.javaClass.simpleName} processing: {}", work.workConfiguration.namespace)
          when (work.action) {
            Action.MARK -> handler.mark(work.workConfiguration)
            Action.NOTIFY -> handler.notify(work.workConfiguration)
            Action.DELETE -> handler.delete(work.workConfiguration)
            else -> log.warn("Unknown action {}", work.action.name)
          }

          track(work, success = true)
        }
      } catch (e: Exception) {
        log.error("Failed to process: {}", work, e)
        track(work, success = false)
      }
    }

    registry.timer(
      workDurationId.withTags(
        "configuration", work.workConfiguration.namespace,
        "action", work.action.name
      )
    ).record(elapsedTimeMillis, TimeUnit.MILLISECONDS)
  }

  private fun track(work: WorkItem, success: Boolean) {
    registry.counter(
      workId.withTags(
        "success", success.toString(),
        "configuration", work.workConfiguration.namespace,
        "action", work.action.name
      )
    ).increment()
  }

  /**
   * Ensures a worker can process one work item at a time
   */
  private inline fun withLocking(crossinline block: () -> Unit) {
    val lockOptions = LockManager.LockOptions()
      .withLockName(lockingService.ownerName)
      .withVersion(clock.millis())
      .withMaximumLockDuration(lockingService.swabbieMaxLockDuration)

    lockingService.acquireLock(lockOptions) {
      block()
    }
  }
}
