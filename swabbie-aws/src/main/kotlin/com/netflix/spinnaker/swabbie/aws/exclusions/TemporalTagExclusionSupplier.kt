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

import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.exclusions.ExclusionsSupplier
import com.netflix.spinnaker.swabbie.tagging.TemporalTags
import org.springframework.stereotype.Component

@Component
class TemporalTagExclusionSupplier : ExclusionsSupplier {
  override fun get(): List<Exclusion> {
    return listOf(
      Exclusion()
        .withType(ExclusionType.Tag.toString())
        .withAttributes(
          TemporalTags.temporalTags.map { key ->
            Attribute()
              .withKey(key)
              .withValue(
                TemporalTags.supportedTemporalTagValues
              )
          }.toSet()
        )
    )
  }
}
