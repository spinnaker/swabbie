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
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.exclusions.ExclusionsSupplier
import org.springframework.stereotype.Component
import java.time.*
import java.time.temporal.ChronoUnit

@Component
class TemporalTagExclusionSupplier : ExclusionsSupplier {
  companion object {
    fun computeAndCompareAge(excludable: AmazonResource, tagValue: String, target: String): AgeCompareResult {
      if (target.startsWith("pattern:")) {
        target.split(":").last().toRegex().find(tagValue)?.groupValues?.let {
          val unit = it[1]
          val suppliedAmountWithoutUnit = it[0].replace(unit, "").toLong()
          supportedTemporalUnits[unit]?.between(
            Instant.ofEpochMilli(excludable.createTs),
            Instant.now()
          )?.let { elapsedSinceCreation ->
            val computedTargetStamp = LocalDate.now()
              .plus(suppliedAmountWithoutUnit, supportedTemporalUnits[unit])
              .atStartOfDay(ZoneId.systemDefault())
              .toInstant()
              .toEpochMilli()

            return if (suppliedAmountWithoutUnit >= elapsedSinceCreation) {
              AgeCompareResult(
                age = Age.OLDER,
                suppliedStamp = computedTargetStamp,
                comparedStamp = excludable.createTs
              )
            } else {
              AgeCompareResult(
                age = Age.YOUNGER,
                suppliedStamp = computedTargetStamp,
                comparedStamp = excludable.createTs
              )
            }
          }
        }
      }

      return if (tagValue == "never") {
        AgeCompareResult(
          age = Age.INFINITE,
          suppliedStamp = null,
          comparedStamp = excludable.createTs
        )
      } else {
        AgeCompareResult(
          age = Age.UNKNOWN,
          suppliedStamp = null,
          comparedStamp = excludable.createTs
        )
      }
    }

    val temporalTags = listOf("expiration_time", "expires", "ttl")
    private val supportedTemporalUnits = mapOf(
      "d" to ChronoUnit.DAYS,
      "m" to ChronoUnit.MONTHS,
      "y" to ChronoUnit.YEARS,
      "w" to ChronoUnit.WEEKS
    )
  }

  private val supportedTemporalTagValues = listOf("pattern:^\\d+(d|m|y|w)$", "never")

  override fun get(): List<Exclusion> {
    return listOf(
      Exclusion()
      .withType(ExclusionType.Tag.toString())
      .withAttributes(
        temporalTags.map { key ->
          Attribute()
            .withKey(key)
            .withValue(
              supportedTemporalTagValues
            )
        }
      )
    )
  }
}

enum class Age {
  INFINITE, OLDER, EQUAL, YOUNGER, UNKNOWN
}

data class AgeCompareResult(
  val age: Age,
  val suppliedStamp: Long?,
  val comparedStamp: Long
)
