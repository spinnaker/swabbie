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

import com.netflix.spinnaker.swabbie.aws.exclusions.Age
import com.netflix.spinnaker.swabbie.aws.exclusions.TemporalTagExclusionSupplier
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.stereotype.Component

/**
 * A general rule that applies when temporal tags are found on an amazon resource
 * When the resolved resource age is older than today, the resource will be marked for deletion if
 * [WorkConfiguration.rule] attribues match the resource
 */
@Component
class TemporalThresholdRule<T : AmazonResource>(
  private val workConfigurations: List<WorkConfiguration>
) : Rule<T> {
  private val temporalRegex = "pattern:^\\\\d+(d|m|y|w)\$\""
  override fun apply(resource: T): Result {
    val tags = resource.details["tags"] as? List<Map<String, String>> ?: return Result(null)
    val workConfiguration: WorkConfiguration? = workConfigurations.find {
      it.resourceType == resource.resourceType
    } ?: return Result(null)

    if (workConfiguration!!.rule == null) {
      return Result(null)
    }

    // should only apply if properties in the rule are matched on the resource
    if (workConfiguration.rule!!.name == "Tag") {
      for (attribute in workConfiguration.rule!!.attributes) {
        val matchedTag = tags.find { tag ->
          tag.keys.any { it == attribute.key && tag[attribute.key] in attribute.value }
        }

        if (matchedTag != null) {
          TemporalTagExclusionSupplier.temporalTags.forEach { key ->
            resource.getTagValue(key)?.let {
              val ageResult = TemporalTagExclusionSupplier.computeAndCompareAge(resource, it, temporalRegex)
              if (ageResult.age == Age.OLDER) {
                return Result(
                  Summary(
                    description = "${resource.resourceType}  ${resource.resourceId} is tagged for deletion.",
                    ruleName = javaClass.simpleName
                  )
                )
              }
            }
          }
        }
      }
    }

    return Result(null)
  }
}
