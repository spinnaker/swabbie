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

package com.netflix.spinnaker.swabbie.tagging

import com.netflix.spinnaker.swabbie.model.BasicTag
import java.time.temporal.ChronoUnit

class TemporalTags {
  companion object {
    val supportedTemporalTagValues = listOf("^\\d+(d|m|y|w)$", "never")
    val temporalTags = listOf("expiration_time", "expires", "ttl")

    private val supportedTemporalUnits = mapOf(
      "d" to ChronoUnit.DAYS,
      "m" to ChronoUnit.MONTHS,
      "y" to ChronoUnit.YEARS,
      "w" to ChronoUnit.WEEKS
    )

    fun toTemporalPair(tag: BasicTag): Pair<Long, ChronoUnit?> {
      val tagValue = tag.value.toString()
      val unit = tagValue.last().toString()
      val amount = tagValue.replace(unit, "").toLong()
      if (unit !in supportedTemporalUnits) {
        return Pair(amount, null)
      }

      return Pair(amount, supportedTemporalUnits[unit])
    }
  }
}
