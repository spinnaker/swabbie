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

package com.netflix.spinnaker.swabbie.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.config.REDIS_CHUNK_SIZE
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repository.TaskState
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import redis.clients.jedis.ScanParams
import redis.clients.jedis.ScanResult
import java.time.Clock
import java.util.concurrent.TimeUnit

@Component
class RedisTaskTrackingRepository(
  redisClientSelector: RedisClientSelector,
  private val objectMapper: ObjectMapper,
  private val clock: Clock
) : TaskTrackingRepository {

  private val log: Logger = LoggerFactory.getLogger(javaClass)

  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val SUBMITTED_TASKS_KEY = "{swabbie:submitted}"
  private val TASK_STATUS_KEY = "{swabbie:taskstatus}"

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun add(taskId: String, taskCompleteEventInfo: TaskCompleteEventInfo) {
    if (taskCompleteEventInfo.submittedTimeMillis == null) {
      taskCompleteEventInfo.submittedTimeMillis = clock.instant().toEpochMilli()
    }

    taskCompleteEventInfo
      .apply {
        log.info("Orchestrated task $taskId for ${action.name} on resources: ${markedResources.map { it.uniqueId() }}")
      }

    redisClientDelegate.withCommandsClient { client ->
      client.hset(SUBMITTED_TASKS_KEY, taskId, objectMapper.writeValueAsString(taskCompleteEventInfo))
      client.hset(TASK_STATUS_KEY, taskId, TaskState.IN_PROGRESS.toString())
    }
  }

  override fun isInProgress(taskId: String): Boolean {
    return redisClientDelegate.withCommandsClient<Boolean> { client ->
      client.hget(TASK_STATUS_KEY, taskId) == TaskState.IN_PROGRESS.toString()
    }
  }

  override fun setSucceeded(taskId: String) {
    redisClientDelegate.withCommandsClient { client ->
      client.hset(TASK_STATUS_KEY, taskId, TaskState.SUCCEEDED.toString())
    }
  }

  override fun setFailed(taskId: String) {
    redisClientDelegate.withCommandsClient { client ->
      client.hset(TASK_STATUS_KEY, taskId, TaskState.FAILED.toString())
    }
  }

  override fun getInProgress(): Set<String> {
    return getAll(TASK_STATUS_KEY)
      .filterValues { it == TaskState.IN_PROGRESS.toString() }
      .keys
      .toSet()
  }

  override fun getFailed(): Set<String> {
    return getAll(TASK_STATUS_KEY)
      .filterValues { it == TaskState.FAILED.toString() }
      .keys
      .toSet()
  }

  override fun getSucceeded(): Set<String> {
    return getAll(TASK_STATUS_KEY)
      .filterValues { it == TaskState.SUCCEEDED.toString() }
      .keys
      .toSet()
  }

  private fun getAll(key: String): Map<String, String> {
    return redisClientDelegate.withCommandsClient<Map<String, String>> { client ->
      val results = mutableMapOf<String, String>()
      val scanParams: ScanParams = ScanParams().count(REDIS_CHUNK_SIZE)
      var cursor = "0"
      var shouldContinue = true

      while (shouldContinue) {
        val scanResult: ScanResult<Map.Entry<String, String>> = client.hscan(key, cursor, scanParams)
        scanResult.result.forEach { entry ->
          results[entry.key] = entry.value
        }
        cursor = scanResult.cursor
        if ("0" == cursor) {
          shouldContinue = false
        }
      }
      results
    }
  }

  override fun getTaskDetail(taskId: String): TaskCompleteEventInfo? {
    return redisClientDelegate.run {
      this.withCommandsClient<String> { client ->
        client.hget(SUBMITTED_TASKS_KEY, taskId)
      }.let { readTaskCompleteInfo(it) }
    }
  }

  private fun getAllBefore(daysToGoBack: Int): Set<String> {
    return getAll(SUBMITTED_TASKS_KEY)
      .filterValues { value ->
        val taskInfo = readTaskCompleteInfo(value)
        if (taskInfo != null) {
          val xDaysAgo = clock.instant().minusMillis(TimeUnit.DAYS.toMillis(daysToGoBack.toLong())).toEpochMilli()
          val submittedTimeMillis = taskInfo.submittedTimeMillis
          if (submittedTimeMillis == null) {
            log.error(
              "Task for resources {} submitted without a submitted time. " +
                "Not counting task as older than $daysToGoBack days.",
              taskInfo.markedResources.map { it.uniqueId() }.toString()
            )
            false
          } else {
            submittedTimeMillis < xDaysAgo
          }
        } else {
          log.error("Task info not found for $value")
          false
        }
      }
      .keys
  }

  // todo eb: this actually cleans up running tasks as well
  override fun cleanUpFinishedTasks(daysToLeave: Int) {
    val allBefore: Set<String> = getAllBefore(daysToLeave)
    if (allBefore.isEmpty()) return

    log.debug("Cleaning ${allBefore.size} tasks")
    redisClientDelegate.withCommandsClient { client ->
      client.hdel(TASK_STATUS_KEY, *allBefore.toTypedArray())
      client.hdel(SUBMITTED_TASKS_KEY, *allBefore.toTypedArray())
    }
  }

  private fun readTaskCompleteInfo(value: String): TaskCompleteEventInfo? {
    var info: TaskCompleteEventInfo? = null
    try {
      info = objectMapper.readValue(value)
    } catch (e: Exception) {
      log.error("Exception reading task complete event info $value in ${javaClass.simpleName}: ", e)
    }
    return info
  }
}
