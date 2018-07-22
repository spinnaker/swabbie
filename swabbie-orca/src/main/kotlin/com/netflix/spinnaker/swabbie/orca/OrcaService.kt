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

package com.netflix.spinnaker.swabbie.orca

import retrofit.http.*

interface OrcaService {
  @POST("/ops")
  @Headers("Content-Type: application/context+json")
  fun orchestrate(@Body request: OrchestrationRequest): TaskResponse

  @GET("/tasks/{id}")
  fun getTask(@Path("id") id: String): TaskDetailResponse
}

data class TaskResponse(
  val ref: String
)

data class TaskDetailResponse(
  val id: String,
  val name: String,
  val application: String,
  val buildTime: String,
  val startTime: String,
  val endTime: String,
  val status: OrcaExecutionStatus
)

enum class OrcaExecutionStatus {
  NOT_STARTED,
  RUNNING,
  SUCCEEDED,
  FAILED_CONTINUE,
  TERMINAL,
  CANCELED,
  STOPPED;

  fun isFailure() = listOf(TERMINAL, FAILED_CONTINUE, STOPPED, CANCELED).contains(this)
  fun isSuccess() = listOf(SUCCEEDED).contains(this)
  fun isIncomplete() = listOf(NOT_STARTED, RUNNING).contains(this)
}

class OrchestrationRequest(
  val application: String,
  val description: String,
  val job: List<OrcaJob>
)

class OrcaJob(
  type: String,
  context: MutableMap<String, Any?>) : HashMap<String, Any?>(context.apply { put("type", type) }
)
