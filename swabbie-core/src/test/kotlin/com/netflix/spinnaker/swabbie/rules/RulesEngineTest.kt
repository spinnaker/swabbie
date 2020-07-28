/*
 *
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
 *
 */

package com.netflix.spinnaker.swabbie.rules

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleConfiguration
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleConfiguration.OPERATOR
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.TestRule
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_PROVIDER_TYPE
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_TYPE
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEmpty

object RulesEngineTest {
  private val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  private val workConfiguration = WorkConfigurationTestHelper
    .generateWorkConfiguration(resourceType = TEST_RESOURCE_TYPE, cloudProvider = TEST_RESOURCE_PROVIDER_TYPE)

  private val resource = TestResource(resourceId = "1", createTs = clock.millis())
  private val applyingRule = TestRule(
    name = "applyingRule",
    invalidOn = { resource, _ -> resource.resourceId == resource.resourceId },
    summary = Summary(description = "test rule", ruleName = "applyingRule")
  )

  private val alwaysApplyingRule = TestRule(
    name = "alwaysApplyingRule",
    invalidOn = { _, _ -> true },
    summary = Summary(description = "test rule", ruleName = "alwaysApplyingRule")
  )

  private val neverApplyingRule = TestRule(
    name = "neverApplyingRule",
    invalidOn = { _, _ -> false },
    summary = Summary(description = "test rule", ruleName = "neverApplyingRule")
  )

  @Test
  fun `should get enabled rules`() {
    val availableRules = listOf(alwaysApplyingRule, neverApplyingRule)
    val orRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.OR
        rules = setOf(
          RuleDefinition().apply {
            name = alwaysApplyingRule.name()
          }
        )
      }

    val workConfiguration = workConfiguration.copy(enabledRules = setOf(orRuleConfig))
    val rulesEngine = ResourceRulesEngine(
      rules = availableRules,
      workConfigurations = listOf(workConfiguration)
    )

    expectThat(rulesEngine.getRules(workConfiguration)).containsExactly(alwaysApplyingRule)
    expectThat(rulesEngine.getRules(workConfiguration.copy(enabledRules = emptySet()))).isEmpty()
  }

  @Test
  fun `should evaluate OR rules (true || false || true = true)`() {
    val availableRules = listOf(alwaysApplyingRule, neverApplyingRule, applyingRule)

    // applies if any of the enabled rules apply
    val orRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.OR
        rules = availableRules.map {
          RuleDefinition()
            .apply { name = it.name() }
        }.toSet()
      }

    val workConfiguration = workConfiguration.copy(enabledRules = setOf(orRuleConfig))
    val rulesEngine = ResourceRulesEngine(
      rules = availableRules,
      workConfigurations = listOf(workConfiguration)
    )

    val appliedRuleNames = rulesEngine.evaluate(resource, workConfiguration)
      .map { it.ruleName }

    expectThat(appliedRuleNames).containsExactlyInAnyOrder(alwaysApplyingRule.name(), applyingRule.name())
  }

  @Test
  fun `should evaluate AND rules (true && false && true = false)`() {
    val availableRules = listOf(alwaysApplyingRule, neverApplyingRule, applyingRule)

    // applies if rules apply
    val andRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.AND
        rules = availableRules.map {
          RuleDefinition()
            .apply { name = it.name() }
        }.toSet()
      }

    val workConfiguration = workConfiguration.copy(enabledRules = setOf(andRuleConfig))
    val rulesEngine = ResourceRulesEngine(
      rules = availableRules,
      workConfigurations = listOf(workConfiguration)
    )

    expectThat(rulesEngine.evaluate(resource, workConfiguration)).isEmpty()
  }

  @Test
  fun `should evaluate AND rules (true && true = true)`() {
    val availableRules = listOf(alwaysApplyingRule, neverApplyingRule, applyingRule)

    // applies if rules apply
    val andRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.AND
        rules = listOf(alwaysApplyingRule, applyingRule).map {
          RuleDefinition()
            .apply { name = it.name() }
        }.toSet()
      }

    val workConfiguration = workConfiguration.copy(enabledRules = setOf(andRuleConfig))
    val rulesEngine = ResourceRulesEngine(
      rules = availableRules,
      workConfigurations = listOf(workConfiguration)
    )

    val appliedRuleNames = rulesEngine.evaluate(resource, workConfiguration)
      .map { it.ruleName }

    expectThat(appliedRuleNames).containsExactlyInAnyOrder(alwaysApplyingRule.name(), applyingRule.name())
  }

  /**
   * ------------------ config sample AND and OR rules---
   * enabledRules:
   - operator: AND
   description: Resource is expired and is disabled
   rules:
   - name: ExpiredResourceRule
   - name: DisabledResourceRule
   - operator: OR
   description: Resource has no application
   rules:
   - name: NoApplicationRule
   *-----------------------------------------------------
   */

  @Test
  fun `should evaluate AND and OR rules ((true && true) || (true || false || true)) = true`() {
    val availableRules = listOf(alwaysApplyingRule, neverApplyingRule, applyingRule)
    val andRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.AND
        rules = listOf(alwaysApplyingRule, applyingRule).map {
          RuleDefinition()
            .apply { name = it.name() }
        }.toSet()
      }

    val orRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.OR
        rules = listOf(alwaysApplyingRule, neverApplyingRule, applyingRule).map {
          RuleDefinition()
            .apply { name = it.name() }
        }.toSet()
      }

    val workConfiguration = workConfiguration.copy(enabledRules = setOf(andRuleConfig, orRuleConfig))
    val rulesEngine = ResourceRulesEngine(
      rules = availableRules,
      workConfigurations = listOf(workConfiguration)
    )

    val appliedRuleNames = rulesEngine.evaluate(resource, workConfiguration)
      .map { it.ruleName }

    expectThat(appliedRuleNames).containsExactlyInAnyOrder(alwaysApplyingRule.name(), applyingRule.name())
  }

  /**
   * ------------------ config sample OR---
   * enabledRules:
   - operator: OR
   description: Applies if resource is older than a year or has expired
   rules:
   - name: ExpiredResourceRule
   - name: AgeRule
   parameters:
   olderThanDays: 365
   *-------------------------------
   */
  @Test
  fun `should evaluate with parameters OR`() {
    // test age rule
    val ageRule = TestRule(
      name = "ageRule",
      invalidOn = { resource, parameters ->
        resource.age(clock).toDays() > (parameters["olderThanDays"] as Int).toLong()
      },
      summary = Summary(description = "Tests if resource is older than olderThanDays", ruleName = "ageRule")
    )

    val neverApplyRuleDefinition = RuleDefinition()
      .apply {
        name = neverApplyingRule.name()
      }

    val ageRuleDefinition = RuleDefinition()
      .apply {
        name = ageRule.name()
        parameters = mapOf("olderThanDays" to 10)
      }

    val availableRules = listOf(ageRule, neverApplyingRule)
    val orRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.OR
        rules = setOf(ageRuleDefinition, neverApplyRuleDefinition)
      }

    val workConfiguration = workConfiguration.copy(enabledRules = setOf(orRuleConfig))
    val rulesEngine = ResourceRulesEngine(
      rules = availableRules,
      workConfigurations = listOf(workConfiguration)
    )

    expectThat(
      rulesEngine.evaluate(resource.copy(createTs = clock.millis()), workConfiguration)
    ).isEmpty()

    val elevenDaysAgo = clock.instant().minus(11, ChronoUnit.DAYS) // > olderThanDays
    val appliedRuleNames = rulesEngine.evaluate(resource.copy(createTs = elevenDaysAgo.toEpochMilli()), workConfiguration)
      .map { it.ruleName }

    expectThat(appliedRuleNames).containsExactly(ageRule.name())
  }

  /**
   * ------------------ config sample AND---
   * enabledRules:
   - operator: AND
   description: Resource is older than a year and has been disabled longer than 30 days
   rules:
   - name: DisabledResourceRule
   parameters:
   longerThanDays: 30
   - name: AgeRule
   parameters:
   olderThanDays: 365
   *-------------------------------
   */
  @Test
  fun `should evaluate with parameters`() {
    // test age rule
    val ageRule = TestRule(
      name = "ageRule",
      invalidOn = { resource, parameters ->
        resource.age(clock).toDays() > (parameters["olderThanDays"] as Int).toLong()
      },
      summary = Summary(description = "Tests if resource is older than olderThanDays", ruleName = "ageRule")
    )

    val alwaysApplyRuleDefinition = RuleDefinition()
      .apply {
        name = alwaysApplyingRule.name()
      }

    val ageRuleDefinition = RuleDefinition()
      .apply {
        name = ageRule.name()
        parameters = mapOf("olderThanDays" to 10)
      }

    val availableRules = listOf(ageRule, alwaysApplyingRule)
    val andRuleConfig = RuleConfiguration()
      .apply {
        operator = OPERATOR.AND
        rules = setOf(ageRuleDefinition, alwaysApplyRuleDefinition)
      }

    val workConfiguration = workConfiguration.copy(enabledRules = setOf(andRuleConfig))
    val rulesEngine = ResourceRulesEngine(
      rules = availableRules,
      workConfigurations = listOf(workConfiguration)
    )

    expectThat(
      rulesEngine.evaluate(resource.copy(createTs = clock.millis()), workConfiguration)
    ).isEmpty()

    val elevenDaysAgo = clock.instant().minus(11, ChronoUnit.DAYS) // > olderThanDays
    val appliedRuleNames = rulesEngine.evaluate(resource.copy(createTs = elevenDaysAgo.toEpochMilli()), workConfiguration)
      .map { it.ruleName }

    expectThat(appliedRuleNames).containsExactly(ageRule.name(), alwaysApplyingRule.name())
  }
}
