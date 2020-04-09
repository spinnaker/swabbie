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
import com.netflix.spinnaker.swabbie.model.BasicTag
import java.time.Clock
import org.springframework.stereotype.Component

@Component
class AmazonTagExclusionPolicy(
  val clock: Clock
) : ResourceExclusionPolicy {
  override fun getType(): ExclusionType = ExclusionType.Tag
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): String? {
    if (excludable !is AmazonResource || excludable.tags().isNullOrEmpty()) {
      return null
    }

    val tags = excludable.tags()!!
    // Exclude this resource if it's tagged with a ttl but has not yet expired
    val temporalTags = tags.filter(BasicTag::isTemporal)
    if (temporalTags.isNotEmpty() && !excludable.expired(clock)) {
      val keysAsString = temporalTags.map { it.key }.joinToString { "," }
      val valuesAsString = temporalTags.map { it.value }.toString()
      return patternMatchMessage(keysAsString, setOf(valuesAsString))
    }

    val configuredKeysAndTargetValues = keysAndValues(exclusions, ExclusionType.Tag)
    tags.forEach { tag ->
      val target = configuredKeysAndTargetValues[tag.key] ?: emptyList()
      if (tag.value in target) {
        return patternMatchMessage(tag.key, setOf(tag.value.toString()))
      }
    }

    return null
  }
}
