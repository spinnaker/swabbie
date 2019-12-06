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

package com.netflix.spinnaker.swabbie.rules

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleConfiguration
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleConfiguration.OPERATOR
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
open class ResourceRulesEngine(
  rules: List<Rule> = emptyList(),
  workConfigurations: List<WorkConfiguration> = emptyList()
) : RulesEngine {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
  private var ruleDefinitionRegistry: Map<RuleConfiguration, Map<RuleDefinition, Rule>>

  init {
    val ruleConfigs = workConfigurations.flatMap {
      it.enabledRules
    }.toSet()

    ruleDefinitionRegistry = buildRuleDefinitions(rules, ruleConfigs)
    log.info("Registered Rules: $ruleDefinitionRegistry")
  }

  override fun getRules(workConfiguration: WorkConfiguration): List<Rule> {
    return workConfiguration.enabledRules
      .mapNotNull { ruleConfig ->
        ruleDefinitionRegistry[ruleConfig]?.values
      }.flatten()
  }

  /**
   * Evaluates a resource against enabled rules via configuration
   * @see [com.netflix.spinnaker.swabbie.model.WorkConfiguration.enabledRules]
   * @see [getViolations]
   */
  override fun <T : Resource> evaluate(resource: T, workConfiguration: WorkConfiguration): List<Summary> {
    if (workConfiguration.enabledRules.isNullOrEmpty()) {
      return emptyList()
    }

    val violations = workConfiguration
      .enabledRules
      .flatMap { ruleConfig ->
        resource.getViolations(ruleConfig, resource.javaClass)
      }.toSet()

    return violations.toList()
  }

  private fun RuleConfiguration.ruleDefinitions(): Map<RuleDefinition, Rule> {
    return ruleDefinitionRegistry[this] ?: emptyMap()
  }

  /**
   * Applies rules defined in configuration and returns resulting violations
   * ------------------ yaml config sample AND and OR rules---
   * enabledRules:
      - operator: AND
        description: Resource is expired and is disabled
        rules:
          - name: ExpiredResourceRule
          - name: DisabledResourceRule
      - operator: OR
        description: Resource has no application or is older than a year
        rules:
          - name: NoApplicationRule
          - name: AgeRule
            parameters:
              olderThanDays: 365
   *-----------------------------------------------------
   *
   * This configuration evaluates to: if ((ExpiredResourceRule && DisabledResourceRule) || (NoApplicationRule || AgeRule))
   * as hinted in the description of each rule configuration.
   */
  private fun <T : Resource> T.getViolations(ruleConfig: RuleConfiguration, type: Class<T>): List<Summary> {
    val violationSummaries = mutableSetOf<Summary?>()
    for ((ruleDefinition, rule) in ruleConfig.ruleDefinitions()) {
      if (!rule.applicableForType(type)) {
        log.warn("Skipping {}. Found non-applicable rule: $ruleConfig for resourceType: $resourceType")
        return emptyList()
      }

      val violation = rule.apply(this, ruleDefinition).summary
      if (ruleConfig.operator == OPERATOR.AND && violation == null) {
        return emptyList()
      }

      violationSummaries.add(violation)
    }

    return violationSummaries.filterNotNull()
  }

  private fun List<Rule>.ensureFindAtMostOneRule(name: String): Rule? {
    return filter {
      it.name() == name
    }.also { result ->
      check(result.size <= 1) { "Found duplicate rule config: $result for $name" }
    }.first()
  }

  /**
   * Builds a graph of rule configurations and rule definitions.
   * Used to pre compute @property [ruleDefinitionRegistry]
   */
  private fun buildRuleDefinitions(
    rules: List<Rule>,
    ruleConfigs: Set<RuleConfiguration>
  ): Map<RuleConfiguration, Map<RuleDefinition, Rule>> {
    val ruleConfigurationDefinitionPairs = mutableMapOf<RuleConfiguration, Map<RuleDefinition, Rule>>()
    for (ruleConfig in ruleConfigs) {
      val definitions = mutableMapOf<RuleDefinition, Rule>()
      for (ruleDefinition in ruleConfig.rules) {
        val rule = rules.ensureFindAtMostOneRule(ruleDefinition.name)
        if (rule != null) {
          definitions.putIfAbsent(ruleDefinition, rule)
        }
      }

      ruleConfigurationDefinitionPairs[ruleConfig] = definitions
    }

    return ruleConfigurationDefinitionPairs.toMap()
  }
}

interface RulesEngine {
  fun getRules(workConfiguration: WorkConfiguration): List<Rule>
  fun <T : Resource> evaluate(resource: T, workConfiguration: WorkConfiguration): List<Summary>
}
