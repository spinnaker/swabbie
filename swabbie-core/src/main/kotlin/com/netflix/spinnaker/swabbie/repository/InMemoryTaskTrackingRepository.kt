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

package com.netflix.spinnaker.swabbie.repository

import java.time.Clock
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class InMemoryTaskTrackingRepository(
  private val clock: Clock
) : TaskTrackingRepository {

  private val submittedTasks = HashMap<String, TaskCompleteEventInfo>()
  private val taskStatus = HashMap<String, TaskState>()

  private val log = LoggerFactory.getLogger(javaClass)

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun add(taskId: String, taskCompleteEventInfo: TaskCompleteEventInfo) {
    if (taskCompleteEventInfo.submittedTimeMillis == null) {
      taskCompleteEventInfo.submittedTimeMillis = clock.instant().toEpochMilli()
    }

    submittedTasks.put(taskId, taskCompleteEventInfo)
    taskStatus.put(taskId, TaskState.IN_PROGRESS)
  }

  override fun isInProgress(taskId: String): Boolean {
    return taskStatus[taskId] == TaskState.IN_PROGRESS
  }

  override fun getInProgress(): Set<String> {
    return taskStatus.filterValues { it == TaskState.IN_PROGRESS }.keys
  }

  override fun setSucceeded(taskId: String) {
    taskStatus[taskId] = TaskState.SUCCEEDED
  }

  override fun setFailed(taskId: String) {
    taskStatus[taskId] = TaskState.FAILED
  }

  override fun getFailed(): Set<String> {
    return taskStatus.filterValues { it == TaskState.FAILED }.keys
  }

  override fun getSucceeded(): Set<String> {
    return taskStatus.filterValues { it == TaskState.SUCCEEDED }.keys
  }

  override fun getTaskDetail(taskId: String): TaskCompleteEventInfo? {
    return submittedTasks[taskId]
  }

  override fun cleanUpFinishedTasks(daysToLeave: Int) {
    getAllBefore(daysToLeave).forEach { taskId ->
      submittedTasks.remove(taskId)
      taskStatus.remove(taskId)
    }
  }

  private fun getAllBefore(daysToGoBack: Int): Set<String> {
    return submittedTasks
      .filterValues { value ->
        value.let { taskInfo ->
          val xDaysAgo = clock.instant().minusSeconds(TimeUnit.DAYS.toMillis(daysToGoBack.toLong())).toEpochMilli()
          taskInfo.submittedTimeMillis!! < xDaysAgo
        }
      }
      .keys
  }
}
