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

package com.netflix.spinnaker.swabbie.orca.service

import retrofit.http.*

interface OrcaService {

  @POST("/ops")
  @Headers("Content-Type: application/context+json")
  fun orchestrate(@Body request: Map<String, Any>): Map<String, Any>

  @GET("/tasks/{id}")
  fun getTask(@Path("id") id: String): Map<String, Any>
}
