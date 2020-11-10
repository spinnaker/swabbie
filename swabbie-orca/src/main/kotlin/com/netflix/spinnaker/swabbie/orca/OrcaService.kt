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

import java.util.concurrent.TimeUnit
import retrofit.http.Body
import retrofit.http.GET
import retrofit.http.Headers
import retrofit.http.POST
import retrofit.http.Path

interface OrcaService {
  @POST("/ops")
  @Headers("Content-Type: application/context+json")
  fun orchestrate(@Body request: OrchestrationRequest): TaskResponse

  @GET("/tasks/{id}")
  fun getTask(@Path("id") id: String): TaskDetailResponse
}

data class TaskResponse(
  val ref: String
) {
  /**
   * Parses taskId from orca response of format
   * "ref": "/tasks/01CK1Y63QFEP4ETC6P5DARECV6"
   */
  fun taskId(): String =
    ref.substring(ref.lastIndexOf("/") + 1)
}

data class TaskDetailResponse(
  val id: String,
  val name: String,
  val application: String,
  val buildTime: String,
  val startTime: String,
  val endTime: String?,
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
  fun isComplete() = isFailure() || isSuccess()
}

class OrchestrationRequest(
  val application: String,
  val description: String,
  val job: List<OrcaJob>
)

class OrcaJob(
  type: String,
  context: MutableMap<String, Any?>
) : HashMap<String, Any?>(
  context.apply { put("type", type) }
)

/**
 * Returns a random number of seconds, from 0 to [ceiling]
 */
private fun generateRandomWaitTimeS(ceiling: Long): Int {
  val randNumber = Math.random()
  return (ceiling * randNumber).toInt()
}

/**
 * Generates a wait stage with a random wait time between 0 and [ceiling].
 * This stage is meant to be used at the start of an orca task to stagger
 * the start of many delete tasks, and the default [stageId] is 0.
 *
 * Optionally, you can add a [stageId] and [requisiteStageRefIds] to
 * construct a stage you can place anywhere in your pipeline.
 */
fun generateWaitStageWithRandWaitTime(ceiling: Long, stageId: String = "0", requisiteStageRefIds: List<String> = emptyList()) =
  OrcaJob(
    type = "wait",
    context = mutableMapOf(
      "waitTime" to generateRandomWaitTimeS(ceiling),
      "refId" to stageId,
      "requisiteStageRefIds" to requisiteStageRefIds
    )
  )

/**
 * Generates a wait stage with a fixed wait time.
 *
 * This stage is meant to be used at the start of an orca task to stagger
 * the start of many delete tasks, and the default [stageId] is 0.
 *
 * Optionally, you can add a [stageId] and [requisiteStageRefIds] to
 * construct a stage you can place anywhere in your pipeline.
 */
fun generatedWaitStageWithFixedWaitTime(waitTimeMs: Long, stageId: String = "0", requisiteStageRefIds: List<String> = emptyList()) =
  OrcaJob(
    type = "wait",
    context = mutableMapOf(
      "waitTime" to TimeUnit.MILLISECONDS.toSeconds(waitTimeMs),
      "refId" to stageId,
      "requisiteStageRefIds" to requisiteStageRefIds
    )
  )
