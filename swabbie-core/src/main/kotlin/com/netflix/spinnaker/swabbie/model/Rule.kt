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

package com.netflix.spinnaker.swabbie.model

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition

/**
 * A resource specific rule
 * If the rule finds the resource to be invalid, it will return a violation summary
 * Rules should be kept to simple logic and not perform any I/O operations
 */
interface Rule {
  fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = false
  fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition? = null): Result
  fun name(): String = this.javaClass.simpleName
}

data class Result(
  val summary: Summary?
)

data class Summary(
  val description: String,
  val ruleName: String
)
