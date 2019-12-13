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

package com.netflix.spinnaker.swabbie.rules

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class AgeRule(
  private val clock: Clock
) : Rule {
  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = true
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    val age = resource.age(clock).toDays()
    val moreThanDays = ruleDefinition?.parameters?.get("moreThanDays") as? Int
    if (moreThanDays == null || age <= moreThanDays) {
      return Result(null)
    }

    return Result(
      Summary(
        description = "Resource ${resource.resourceId} is older than $moreThanDays days.",
        ruleName = name()
      )
    )
  }
}
