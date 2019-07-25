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
  override fun getType(): ExclusionType = ExclusionType.Tag
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): String? {
    if (excludable !is AmazonResource || excludable.tags().isNullOrEmpty()) {
      return null
    }

    val tags = excludable.tags()!!
    if (excludable.expired()) {
      val temporalTag = tags.find { excludable.expired(it) }!!
      return patternMatchMessage(temporalTag.key, setOf(temporalTag.value as String))
    }

    val configuredKeysAndTargetValues = keysAndValues(exclusions, ExclusionType.Tag)
    tags.forEach { tag ->
      val target = configuredKeysAndTargetValues[tag.key] ?: emptyList()
      if (target.contains(tag.value as String)) {
        return patternMatchMessage(tag.key, setOf(tag.value as String))
      }
    }

    return null
  }
}
