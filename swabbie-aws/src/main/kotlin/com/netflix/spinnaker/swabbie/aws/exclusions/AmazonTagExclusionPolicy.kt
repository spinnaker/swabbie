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
import com.netflix.spinnaker.swabbie.exclusions.PropertyResolver
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import org.springframework.stereotype.Component

@Component
class AmazonTagExclusionPolicy(
  override val propertyResolvers: List<PropertyResolver>? = null
) : ResourceExclusionPolicy {
  private val tagsField = "tags"
  override fun getType(): ExclusionType = ExclusionType.Tag
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): String? {
    if (excludable is AmazonResource) {
      keysAndValues(exclusions, ExclusionType.Tag)
        .let { excludingTags ->
          if (tagsField in excludable.details) {
            (excludable.details[tagsField] as List<Map<*, *>>).map { tag ->
              tag.keys.find { key ->
                excludingTags[key] != null
              }?.let { key ->
                if (key in TemporalTagExclusionSupplier.temporalTags) {
                  excludingTags[key]!!.map { target ->
                    TemporalTagExclusionSupplier
                      .computeAndCompareAge(
                        excludable = excludable,
                        tagValue = tag[key] as String,
                        target = target
                      ).let {
                        when {
                          it.age == Age.OLDER || it.age == Age.INFINITE ->
                            return patternMatchMessage(tag[key] as String, excludingTags[key]!!.toSet())
                          it.age == Age.YOUNGER ->
                            return null
                          else -> {
                            // no need to check age here.
                            log.debug("Resource age comparison with {}. Result: {}", excludable.createTs, it)
                          }
                        }
                    }
                  }
                }

                if (excludingTags[key]!!.contains(tag[key] as? String)) {
                  return patternMatchMessage(tag[key] as String, excludingTags[key]!!.toSet())
                }
              }
            }
          }
        }
    }

    return null
  }
}
