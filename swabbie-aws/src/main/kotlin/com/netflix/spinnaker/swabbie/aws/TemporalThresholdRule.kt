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

import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.BasicTag
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.stereotype.Component

/**
 * The resource will be marked for deletion if:
 *  - The resource is older than the ttl provided through temporal tags ("expiration_time", "expires", "ttl")
 *  - [WorkConfiguration.rule] attributes match the resource's other identifying tag attributes
 * @See [com.netflix.spinnaker.swabbie.model.BasicTag]
 */

@Component
class TemporalThresholdRule<T : AmazonResource>(
  private val workConfigurations: List<WorkConfiguration>
) : Rule<T> {
  override fun apply(resource: T): Result {
    val tags: List<BasicTag> = resource.tags() ?: return Result(null)
    val workConfiguration: WorkConfiguration = workConfigurations.find {
      it.resourceType == resource.resourceType
    } ?: return Result(null)

    val rule = workConfiguration.rule ?: return Result(null)
    if (rule.name != "Tag") {
      return Result(null)
    }

    val matched = rule.attributes
      .any { attribute ->
        tags.any { tag ->
          tag.key == attribute.key && tag.value in attribute.value
        }
      }

    if (resource.expired() && matched) {
      return Result(
        Summary(
          description = "${resource.resourceType} ${resource.resourceId} is tagged with a expiration time",
          ruleName = javaClass.simpleName
        )
      )
    }

    return Result(null)
  }
}
