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

package com.netflix.spinnaker.config

import com.netflix.spinnaker.swabbie.model.EmptyNotificationConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * @param minImagesUsedByLC minimum number of images used by launch configurations that should be
 *  in the cache. If not present agents will error to prevent mass marking in the case of edda problems.
 * @param minImagesUsedByInst minimum number of images used by instances that should be
 *  in the cache. If not present agents will error to prevent mass marking in the case of edda problems.
 */
@ConfigurationProperties("swabbie")
open class SwabbieProperties {
  var dryRun: Boolean = true
  var outOfUseThresholdDays: Int = 30
  var providers: List<CloudProviderConfiguration> = mutableListOf()
  var schedule: Schedule = Schedule()
  var testing: Testing = Testing()
  var minImagesUsedByLC = 500
  var minImagesUsedByInst = 500
  var maxUnmarkedPerCycle = 250
}

class Testing {
  var alwaysCleanRuleConfig: AlwaysCleanRuleConfig = AlwaysCleanRuleConfig()
}

class AlwaysCleanRuleConfig {
  var enabled: Boolean = false
  var resourceIds: MutableList<String> = mutableListOf()
}

class CloudProviderConfiguration {
  var exclusions: MutableList<Exclusion>? = null
  var name: String = ""
  var locations: List<String> = mutableListOf()
  var accounts: List<String> = mutableListOf()
  var resourceTypes: List<ResourceTypeConfiguration> = mutableListOf()
  var maxItemsProcessedPerCycle: Int = 10
  var itemsProcessedBatchSize: Int = 5
  override fun toString(): String {
    return "CloudProviderConfiguration(" +
      "exclusions=$exclusions, name='$name', locations=$locations, accounts=$accounts, resourceTypes=$resourceTypes, " +
      "maxItemsProcessedPerCycle=$maxItemsProcessedPerCycle, itemsProcessedBatchSize=$itemsProcessedBatchSize)"
  }
}

/**
 * Notifications have both an [enabled] and a [required] configuration.
 *  [enabled] is whether or not the notification agent runs for this configuration.
 *  [required] is whether or not a resource must be notified before it can be deleted.
 *
 * If [enabled] is true and [required] is true then notifications will be sent to the owner.
 *
 * If [enabled] is true and [required] is false a resource will be marked as 'notification not needed'
 *  and will be able to be deleted. No notifications will be sent.
 *
 * If [enabled] is false then the notification agent will not run for this configuration
 *  and resources of this type won't be able to be deleted.
 *
 *  TODO EB: Remove the hard dependency on notifications for all resources.
 *   Instead, make this configurable in a logical and flexible way.
 */
open class NotificationConfiguration(
  var enabled: Boolean = false,
  var required: Boolean = true,
  var types: MutableList<String> = mutableListOf("Email"),
  var optOutBaseUrl: String = "",
  var itemsPerMessage: Int = 10,
  var resourceUrl: String = "",
  var defaultDestination: String = "swabbie@spinnaker.io"
) {
  override fun toString(): String {
    return "NotificationConfiguration(" +
      "enabled=$enabled, types=$types, optOutBaseUrl='$optOutBaseUrl', itemsPerMessage=$itemsPerMessage, " +
      "resourceUrl='$resourceUrl', defaultDestination='$defaultDestination')"
  }
}

class Exclusion {
  var type: String = ""
  var attributes: List<Attribute> = mutableListOf()
  override fun toString(): String {
    return "Exclusion(type='$type', attributes=$attributes)"
  }

  fun withType(t: String): Exclusion =
    this.apply {
      type = t
    }

  fun withAttributes(attrs: List<Attribute>): Exclusion =
    this.apply {
      attributes = attrs
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as Exclusion

    if (type != other.type) return false
    if (attributes != other.attributes) return false

    return true
  }

  override fun hashCode(): Int {
    var result = type.hashCode()
    result = 31 * result + attributes.hashCode()
    return result
  }
}

class ResourceTypeConfiguration {
  var enabled: Boolean = false
  var dryRun: Boolean = true
  var retention: Int = 14
  var exclusions: MutableList<Exclusion> = mutableListOf()
  lateinit var name: String
  var entityTaggingEnabled: Boolean = false
  var maxAge: Int = 14
  var notification: NotificationConfiguration = EmptyNotificationConfiguration()
}

class Attribute {
  var key: String = ""
  var value: List<String> = mutableListOf()
  override fun toString(): String {
    return "Attribute(key='$key', value=$value)"
  }

  fun withKey(k: String): Attribute =
    this.apply {
      key = k
    }

  fun withValue(v: List<String>): Attribute =
    this.apply {
      value = v
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as Attribute

    if (key != other.key) return false
    if (value != other.value) return false

    return true
  }

  override fun hashCode(): Int {
    var result = key.hashCode()
    result = 31 * result + value.hashCode()
    return result
  }
}

/**
 * Default operating hours schedule
 */
class Schedule {
  var enabled: Boolean = true
  var startTime: String = "09:00"
  var endTime: String = "16:00"
  val allowedDaysOfWeek: MutableList<DayOfWeek> = mutableListOf()
  var defaultTimeZone: String = "America/Los_Angeles"

  fun getResolvedDays(): List<DayOfWeek> {
    return if (!enabled) {
      mutableListOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
      )
    } else if (allowedDaysOfWeek.isEmpty()) {
      mutableListOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY
      )
    } else {
      allowedDaysOfWeek
    }
  }

  fun getResolvedStartTime(): LocalTime {
    return LocalTime.parse(startTime)
  }

  fun getResolvedEndTime(): LocalTime {
    return LocalTime.parse(endTime)
  }

  fun getZoneId(): ZoneId {
    return ZoneId.of(defaultTimeZone)
  }

  /**
   * Returns proposed timestamp or next time in window
   */
  fun getNextTimeInWindow(proposedTimestamp: Long): Long {
    return if (!enabled || getResolvedDays().isEmpty()) {
      proposedTimestamp
    } else {
      var day = dayFromTimestamp(proposedTimestamp)
      var incDays = 0
      while (!getResolvedDays().contains(day)) {
        day = day.plus(1)
        incDays += 1
      }
      proposedTimestamp.plus(Duration.ofDays(incDays.toLong()).toMillis())
    }
  }

  private fun dayFromTimestamp(timestamp: Long): DayOfWeek {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.of(defaultTimeZone)).dayOfWeek
  }
}

enum class ExclusionType {
  Tag,
  Allowlist,
  Application,
  Literal,
  Account,
  Naive
}
