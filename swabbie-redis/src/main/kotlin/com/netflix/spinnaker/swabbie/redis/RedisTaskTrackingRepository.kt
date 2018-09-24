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
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.repositories.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repositories.TaskState
import com.netflix.spinnaker.swabbie.repositories.TaskTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.TimeUnit

@Component
class RedisTaskTrackingRepository(
  @Qualifier("redisClientSelector") redisClientSelector: RedisClientSelector,
  private val objectMapper: ObjectMapper,
  private val clock: Clock
) : TaskTrackingRepository {

  private val log: Logger = LoggerFactory.getLogger(javaClass)

  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val SUBMITTED_TASKS_KEY = "{swabbie:submitted}"
  private val TASK_STATUS_KEY = "{swabbie:taskstatus}"

  override fun add(taskId: String, taskCompleteEventInfo: TaskCompleteEventInfo) {
    if (taskCompleteEventInfo.submittedTimeMillis == null) {
      taskCompleteEventInfo.submittedTimeMillis = clock.instant().toEpochMilli()
    }

    redisClientDelegate.withCommandsClient { client ->
      client.hset(SUBMITTED_TASKS_KEY, taskId, objectMapper.writeValueAsString(taskCompleteEventInfo))
      client.hset(TASK_STATUS_KEY, taskId, TaskState.IN_PROGRESS.toString())
    }
  }

  override fun isInProgress(taskId: String): Boolean {
    redisClientDelegate.run {
      return this.withCommandsClient<Boolean> { client ->
        client.hget(TASK_STATUS_KEY, taskId) == TaskState.IN_PROGRESS.toString()
      }
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
    return redisClientDelegate.run {
      this.withCommandsClient<Map<String, String>> { client ->
        client.hgetAll(key)
      }
    }
  }

  override fun getTaskDetail(taskId: String): TaskCompleteEventInfo? {
    return redisClientDelegate.run {
      this.withCommandsClient<String> { client ->
        client.hget(SUBMITTED_TASKS_KEY, taskId)
      }.let {
        objectMapper.readValue(it, TaskCompleteEventInfo::class.java)
      }
    }
  }

  private fun getAllBefore(daysToGoBack: Int): Set<String> {
    return getAll(SUBMITTED_TASKS_KEY)
      .filterValues { value ->
        objectMapper.readValue(value, TaskCompleteEventInfo::class.java).let { taskInfo ->
          val xDaysAgo = clock.instant().minusMillis(TimeUnit.DAYS.toMillis(daysToGoBack.toLong())).toEpochMilli()
          taskInfo.submittedTimeMillis!! < xDaysAgo
        }
      }
      .keys
  }

  //todo eb: this actually cleans up running tasks as well
  override fun cleanUpFinishedTasks(daysToLeave: Int) {
    val allBefore: Set<String> = getAllBefore(daysToLeave)
    log.debug("Cleaning ${allBefore.size} tasks")
    allBefore.forEach { taskId ->
      redisClientDelegate.withCommandsClient { client ->
        client.hdel(TASK_STATUS_KEY, taskId)
        client.hdel(SUBMITTED_TASKS_KEY, taskId)
      }
    }
  }
}
