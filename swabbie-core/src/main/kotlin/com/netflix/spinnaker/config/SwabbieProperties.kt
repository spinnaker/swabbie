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
import com.netflix.spinnaker.swabbie.model.SoftDelete
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

@ConfigurationProperties("swabbie")
open class SwabbieProperties {
  var dryRun: Boolean = true
  var providers: List<CloudProviderConfiguration> = mutableListOf()
  var schedule: Schedule = Schedule()
  var testing: Testing = Testing()
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

open class NotificationConfiguration(
  var enabled: Boolean = false,
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
  var softDelete: SoftDelete = SoftDelete()
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
  var endTime: String = "15:00"
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
}

enum class ExclusionType {
  Tag,
  Allowlist,
  Application,
  Literal,
  Account,
  Naive
}
