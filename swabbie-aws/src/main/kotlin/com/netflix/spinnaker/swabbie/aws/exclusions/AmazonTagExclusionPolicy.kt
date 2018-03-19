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

package com.netflix.spinnaker.swabbie.aws.exclusions

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.exclusions.Excludable
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import org.springframework.stereotype.Component

@Component
class AmazonTagExclusionPolicy : ResourceExclusionPolicy {
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): Boolean {
    if (excludable is AmazonResource) {
      keysAndValues(exclusions, ExclusionType.Tag)
        .let { excludingTags ->
          if ("tags" in excludable.details) {
            (excludable.details["tags"] as? List<Map<*, *>>)?.map { tag ->
              return tag.keys.any { key ->
                excludingTags[key] != null && excludingTags[key]!!.contains(tag[key])
              }
            }
          }
        }
    }

    return false
  }
}
