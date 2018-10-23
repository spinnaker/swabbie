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

package com.netflix.spinnaker.swabbie.exclusions

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.model.Identifiable
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.NoSuchElementException
import kotlin.reflect.full.memberProperties

interface ExclusionPolicy {
  val log: Logger
    get() = LoggerFactory.getLogger(javaClass)

  /**
   * Returns a reason if this policy applies, null otherwise.
   */
  fun apply(excludable: Excludable, exclusions: List<Exclusion>): String?

  fun getType(): ExclusionType

  fun notAllowlistedMessage(value: String?, patterns: Set<Any> = emptySet()): String {
    return "$value not in allowlist. Matched: ${patterns.joinToString(",")}"
  }

  fun patternMatchMessage(value: String, patterns: Set<String> = emptySet()): String {
    return "$value doesn't match pattern. Matched: ${patterns.joinToString(",")}"
  }

  fun wildcardMatchMessage(value: String, patterns: Set<String> = emptySet()): String {
    return "$value matches wildcard excluding $value. Matched: ${patterns.joinToString(",")}"
  }

  fun String.matchPattern(p: String): Boolean =
    p.startsWith("pattern:") && this.contains(p.split(":").last().toRegex())

  fun byPropertyMatchingResult(
    exclusions: List<Exclusion>,
    excludable: Excludable,
    exclusionType: ExclusionType = getType()
  ): String? {
    keysAndValues(exclusions, exclusionType).let { kv ->
      kv.values.toSet().flatten().let { exclusionValues ->
        if (exclusionValues.size == 1 && exclusionValues[0] == "\\*") {
          return wildcardMatchMessage(excludable.name!!, exclusionValues.toSet())
        }
      }

      // match on property name
      kv.keys.forEach { key ->
        findProperty(excludable, key, kv[key]!!)?.let {
          return patternMatchMessage(key, setOf(it))
        }
      }
    }

    return null
  }

  fun findProperty(excludable: Excludable, key: String, values: List<String>): String? {
    try {
      val fieldValue = getProperty(excludable, key) as? String
      if (propertyMatches(values, fieldValue)) {
        return fieldValue
      }
    } catch (e: IllegalArgumentException) {
      log.warn("Object has no property name $key")
    }

    return null
  }

  fun <R: Any?> getProperty(instance: Any, propertyName: String): R {
    try {
      return readPropery(instance, propertyName)
    } catch (e: NoSuchElementException) {
      val details: Map<String, Any?>? = readPropery(instance, "details")
      if (details != null) {
        return details[propertyName] as R
      }

      throw e
    }
  }

  private fun <R: Any?> readPropery(instance: Any, propertyName: String): R {
    @Suppress("UNCHECKED_CAST")
    return instance.javaClass.kotlin.memberProperties.first { it.name == propertyName }.get(instance) as R
  }

  /**
   * Takes a list of config-defined exlusions.
   * For each exclusion that matches the type we're considering,
   * transform all information into a key,values that make up this policy
   */
  fun keysAndValues(exclusions: List<Exclusion>, type: ExclusionType): Map<String, List<String>> {
    val map = mutableMapOf<String, List<String>>()
    exclusions.filter {
      it.type.equals(type.name, true)
    }.forEach {
      it.attributes.forEach {
        map[it.key] = it.value
      }
    }

    return map
  }

  private fun propertyMatches(values: List<String>, fieldValue: String?): Boolean {
    if (fieldValue == null) {
      return false
    }
    val splitFieldValue = fieldValue.split(",").map { it.trim() }

    return values.contains(fieldValue) ||
      values.any { fieldValue.matchPattern(it) || splitFieldValue.contains(it) }
  }
}

internal fun shouldExclude(excludable: Excludable,
                           workConfiguration: WorkConfiguration,
                           exclusionPolicies: List<ExclusionPolicy>,
                           log: Logger): Boolean {
  return excludable.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions).also {
    if (it.excluded) {
      log.info("Excluding resource because reasons: {}, resource: {}", it.reasons, excludable)
    }
  }.excluded
}

data class ExclusionResult(
  val excluded: Boolean,
  val reasons: Set<String>
)

interface Excludable : Identifiable {
  /**
   * @param exclusionPolicies: all possible policies defined in code
   * @param exclusions: actual configured policies based on swabbie.yml
   */
  fun shouldBeExcluded(exclusionPolicies: List<ExclusionPolicy>, exclusions: List<Exclusion>): ExclusionResult {
    exclusionPolicies.mapNotNull { it.apply(this, exclusions) }.let { reasons ->
      return ExclusionResult(!reasons.isEmpty(), reasons.toSet())
    }
  }
}

interface ResourceExclusionPolicy : ExclusionPolicy
interface BasicExclusionPolicy : ExclusionPolicy
