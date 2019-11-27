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

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.swabbie.model.ParameterizedRule
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Summary
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class AgeRule(
  private val clock: Clock
) : ParameterizedRule<Resource> {
  private var olderThanDays: Int? = null
  override fun withParameters(parameters: Map<String, Any>): AgeRule {
    olderThanDays = parameters["olderThanDays"] as? Int
    return this
  }

  override fun apply(resource: Resource): Result {
    if (olderThanDays != null && resource.age(clock).toDays() > olderThanDays!!) {
      return Result(
        Summary(
          description = "Resource ${resource.resourceId} is older than $olderThanDays days.",
          ruleName = name()
        )
      )
    }

    return Result(null)
  }
}
