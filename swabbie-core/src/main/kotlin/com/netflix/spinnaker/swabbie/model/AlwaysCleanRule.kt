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

package com.netflix.spinnaker.swabbie.model

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.config.SwabbieProperties
import org.slf4j.LoggerFactory

/**
 * Testing rule that is invalid on everything.
 */
class AlwaysCleanRule(
  swabbieProperties: SwabbieProperties
) : Rule {
  private val config = swabbieProperties.testing.alwaysCleanRuleConfig
  private val log = LoggerFactory.getLogger(javaClass)

  init {
    log.info("Using ${javaClass.simpleName} for resources ${config.resourceIds}")
  }

  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = true
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    return if (config.resourceIds.contains(resource.resourceId)) {
      Result(
        Summary(
          description = "This resource should always be cleaned",
          ruleName = name()
        )
      )
    } else {
      Result(null)
    }
  }
}
