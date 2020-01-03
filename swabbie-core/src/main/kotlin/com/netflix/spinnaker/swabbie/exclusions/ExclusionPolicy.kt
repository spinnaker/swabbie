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

  fun wildcardMatchMessage(value: String, patterns: Set<Any> = emptySet()): String {
    return "$value matches wildcard excluding $value. Matched: ${patterns.joinToString(",")}"
  }

  fun String.matchPattern(p: String): Boolean =
    p.startsWith("pattern:") && this.contains(p.split(":").last().toRegex())

  /**
   * Returns a reason for the match if the excludable matches any exclusions.
   * Takes care of exact match or pattern match.
   */
  fun byPropertyMatchingResult(
    exclusions: List<Exclusion>,
    excludable: Excludable,
    exclusionType: ExclusionType = getType()
  ): String? {
    val kv: Map<String, List<Any>> = keysAndValues(exclusions, exclusionType)
    val exclusionValues = kv.values.toSet().flatten()

    if (exclusionValues.size == 1 && exclusionValues[0] == "\\*") {
      return wildcardMatchMessage(excludable.name!!, exclusionValues.toSet())
    }

    // match on property name
    kv.keys.forEach { key ->
      val matchingValues: List<Any?> = kv.getValue(key)
      if (excludable.matchResourceAttributes(key, matchingValues)) {
        return patternMatchMessage(key, matchingValues.map { "$it" }.toSet())
      }
    }

    return null
  }

  /**
   * Takes a list of config-defined exlusions.
   * For each exclusion that matches the type we're considering,
   * transform all information into a key,values that make up this policy
   */
  fun keysAndValues(exclusions: List<Exclusion>, type: ExclusionType): Map<String, List<Any>> {
    val map = mutableMapOf<String, List<Any>>()
    exclusions.filter {
      it.type.equals(type.name, true)
    }.forEach {
      it.attributes.forEach {
        map[it.key] = it.value
      }
    }

    return map
  }

  private fun propertyMatches(values: List<Any>, fieldValue: String?): Boolean {
    if (fieldValue == null) {
      return false
    }
    val splitFieldValue = fieldValue.split(",").map { it.trim() }

    return values.contains(fieldValue) ||
      values.any { it is String && fieldValue.matchPattern(it) || splitFieldValue.contains(it) }
  }
}

internal fun shouldExclude(
  excludable: Excludable,
  workConfiguration: WorkConfiguration,
  exclusionPolicies: List<ExclusionPolicy>,
  log: Logger
): Boolean {
  return excludable.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions.toList()).also {
    if (it.excluded) {
      log.debug("Excluding resource because reasons: {}, resource: {}", it.reasons, excludable)
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
