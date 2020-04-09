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

package com.netflix.spinnaker.swabbie.aws

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import java.time.Clock
import org.springframework.stereotype.Component

/**
 * This rule applies if this amazon resource has expired.
 * A resource is expired if it's tagged with the following keys: ("expiration_time", "expires", "ttl")
 * Acceptable tag value: a number followed by a suffix such as d (days), w (weeks), m (month), y (year)
 * @see com.netflix.spinnaker.swabbie.tagging.TemporalTags.supportedTemporalTagValues
 */

@Component
class ExpiredResourceRule(
  private val clock: Clock
) : Rule {
  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = AmazonResource::class.java.isAssignableFrom(clazz)
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (resource is AmazonResource && resource.expired(clock)) {
      return Result(
        Summary(
          description = "${resource.resourceId} has expired.",
          ruleName = javaClass.simpleName
        )
      )
    }

    return Result(null)
  }
}
