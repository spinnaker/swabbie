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

package com.netflix.spinnaker.swabbie.krieger

import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.LAUNCH_CONFIGURATION
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import retrofit.http.GET

interface KriegerService {
  @GET("/api/applications")
  fun getApplications(): ResolvedApplicationResponse

  companion object {
    val kriegerFieldsToSwabbieTypes = mapOf(
      IMAGE to Pair("images", Collection::class.java),
      LAUNCH_CONFIGURATION to Pair("launchConfigNames", Collection::class.java),
      SERVER_GROUP to Pair("asgNames", Collection::class.java)
    )

    val swabbieTypesTokriegerIdentifiableFields = mapOf(
      IMAGE to "id"
    )
  }
}

data class ResolvedApplicationResponse(
  val _embedded: Map<String, List<Application>>
)
