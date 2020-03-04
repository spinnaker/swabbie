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

/**
  description: "A rule that applies when parameters match a resource's attributes"
  rules:
  - name: AttributeRule
    parameters:
      name: pattern:some
      desc: blah
 */
@Component
class AttributeRule : Rule {
  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = true

  /**
   * @param ruleDefinition parameters map represents a resource's attributes to match against the given values in the map.
   */
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (!resource.matchAttributes(ruleDefinition)) {
      return Result(null)
    }

    return Result(
      Summary(ruleDefinition?.description ?: "(${resource.resourceId}): matched by rule attributes.", name()))
  }

  private fun <T : Resource> T.matchAttributes(ruleDefinition: RuleDefinition?): Boolean {
    // params contains key-value pairs to match against the resource's attributes
    val params = ruleDefinition?.parameters ?: return false
    return params.any { (key, value) -> matchResourceAttributes(key, listOf(value)) }
  }
}
