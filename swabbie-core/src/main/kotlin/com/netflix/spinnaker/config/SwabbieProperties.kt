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

import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.EmptyNotificationConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * @param {@link #minImagesUsedByLC} minimum number of images used by launch configurations that should be
 *  in the cache. If not present agents will error to prevent mass marking in the case of edda problems.
 * @param {@link #minImagesUsedByInst} minimum number of images used by instances that should be
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
  var exclusions: MutableSet<Exclusion>? = null
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
 *  [enabled] on/off flag for notifications
 */
open class NotificationConfiguration(
  var enabled: Boolean = false,
  var types: List<String> = listOf("email"),
  var optOutBaseUrl: String = "",
  var itemsPerMessage: Int = 10,
  var resourceUrl: String = "",
  var defaultDestination: String = "swabbie@spinnaker.io",
  var docsUrl: String = ""
) {
  override fun toString(): String {
    return "NotificationConfiguration(" +
      "enabled=$enabled, types=$types, optOutBaseUrl='$optOutBaseUrl', itemsPerMessage=$itemsPerMessage, " +
      "resourceUrl='$resourceUrl', defaultDestination='$defaultDestination', docsUrl='$docsUrl')"
  }
}

class Exclusion {
  var type: String = ""
  var attributes: MutableSet<Attribute> = mutableSetOf()
  override fun toString(): String {
    return "Exclusion(type='$type', attributes=$attributes)"
  }

  fun withType(t: String): Exclusion =
    this.apply {
      type = t
    }

  fun withAttributes(attrs: Set<Attribute>): Exclusion =
    this.apply {
      attributes = attrs.toMutableSet()
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
  var enabledActions: List<Action> = mutableListOf(Action.MARK, Action.NOTIFY, Action.DELETE)
  var dryRun: Boolean = false
  var retention: Int = 14
  var exclusions: MutableSet<Exclusion> = mutableSetOf()
  lateinit var name: String
  var entityTaggingEnabled: Boolean = false
  var maxAge: Int = 14
  var notification: NotificationConfiguration = EmptyNotificationConfiguration()
  var enabledRules: Set<RuleConfiguration> = setOf()

  /**
   * Allows to specify enabled rules. When defined, all other rules are ignored at runtime.
   * See [com.netflix.spinnaker.swabbie.rules.RulesEngine.evaluate]
   *------------- config snippet for a resource type---------------
          enabledRules:
          - operator: AND
            description: Images with packer tags that are expired
            rules:
            - name: ExpiredResourceRule
            - name: AttributeRule
              parameters:
                description: pattern:^packer-build
   *----------------
   * @property operator ([RuleConfiguration.OPERATOR.OR], [RuleConfiguration.OPERATOR.AND]) dictate how rules are applied
   * @property rules a list of named rules [com.netflix.spinnaker.swabbie.model.Rule]
   */
  class RuleConfiguration {
    enum class OPERATOR {
      OR, // applies if any specified rule applies
      AND // applies only if all specified rules apply
    }

    var operator: OPERATOR = OPERATOR.OR
    var description: String = ""
    var rules: Set<RuleDefinition> = emptySet()
    override fun equals(other: Any?): Boolean {
      if (this === other) {
        return true
      }

      if (javaClass != other?.javaClass) {
        return false
      }

      other as RuleConfiguration

      if (operator != other.operator) {
        return false
      }

      if (description != other.description) {
        return false
      }

      if (rules != other.rules) {
        return false
      }

      return true
    }

    override fun hashCode(): Int {
      var result = operator.hashCode()
      result = 31 * result + description.hashCode()
      result = 31 * result + rules.hashCode()
      return result
    }

    override fun toString(): String {
      return "RuleConfiguration(operator=$operator, description='$description', rules=$rules)"
    }
  }

  class RuleDefinition {
    var name: String = ""
    var parameters: Map<String, Any?> = mapOf()

    override fun equals(other: Any?): Boolean {
      if (this === other) {
        return true
      }

      if (javaClass != other?.javaClass) {
        return false
      }

      other as RuleDefinition
      if (name != other.name) {
        return false
      }

      if (parameters != other.parameters) {
        return false
      }

      return true
    }

    override fun hashCode(): Int {
      var result = name.hashCode()
      result = 31 * result + parameters.hashCode()
      return result
    }

    override fun toString(): String {
      return "RuleDefinition(name='$name', parameters=$parameters)"
    }
  }
}

class Attribute {
  var key: String = ""
  var value: List<Any> = mutableListOf()
  override fun toString(): String {
    return "Attribute(key='$key', value=$value)"
  }

  fun withKey(k: String): Attribute =
    this.apply {
      key = k
    }

  fun withValue(v: List<Any>): Attribute =
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

  // default days overridable in config
  var allowedDaysOfWeek: List<DayOfWeek> = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY
  )

  var defaultTimeZone: String = "America/Los_Angeles"

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
    return if (!enabled) {
      proposedTimestamp
    } else {
      var day = dayFromTimestamp(proposedTimestamp)
      var incDays = 0
      while (!allowedDaysOfWeek.contains(day)) {
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
