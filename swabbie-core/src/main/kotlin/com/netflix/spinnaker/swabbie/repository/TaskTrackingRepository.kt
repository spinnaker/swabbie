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

import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.WorkConfiguration

interface TaskTrackingRepository {
  fun add(taskId: String, taskCompleteEventInfo: TaskCompleteEventInfo)
  fun isInProgress(taskId: String): Boolean
  fun getInProgress(): Set<String>
  fun setSucceeded(taskId: String)
  fun setFailed(taskId: String)
  fun getFailed(): Set<String>
  fun getSucceeded(): Set<String>
  fun getTaskDetail(taskId: String): TaskCompleteEventInfo?
  fun cleanUpTasks(daysToLeave: Int = 2)
}

data class TaskCompleteEventInfo(
  val action: Action,
  val markedResources: List<MarkedResource>,
  val workConfiguration: WorkConfiguration,
  var submittedTimeMillis: Long?
)

enum class TaskState {
  IN_PROGRESS, SUCCEEDED, FAILED
}
