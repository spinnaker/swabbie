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

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.test.TestResource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.isNull

object AttributeRuleTest {
  private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
  private val resource = TestResource(resourceId = "1", createTs = clock.millis())
  private val rule = AttributeRule()

  @Test
  fun `should not apply if missing parameters`() {
    expectThat(rule.apply(resource).summary).isNull()
    val ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = emptyMap()
      }

    expectThat(rule.apply(resource, ruleDefinition).summary).isNull()
  }

  @Test
  fun `should apply`() {
    expectThat(rule.apply(resource).summary).isNull()

    val ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf(
          "name" to "pattern:^bar",
          "resourceId" to "id"
        )
      }

    expectThat(rule.apply(resource, ruleDefinition).summary).isNull()
    expectThat(rule.apply(resource.copy(resourceId = "id"), ruleDefinition).summary).isNotNull()
    expectThat(rule.apply(resource.copy(name = "foo bar"), ruleDefinition).summary).isNull()
    expectThat(rule.apply(resource.copy(name = "bar foo"), ruleDefinition).summary).isNotNull()
  }

  @Test
  fun `should work with non strings properties`() {
    expectThat(rule.apply(resource).summary).isNull()

    val ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf(
          "isValid" to false,
          "version" to 1.0
        )
      }

    expectThat(rule.apply(resource, ruleDefinition).summary).isNull()
    expectThat(rule.apply(resource.withDetail("isValid", false), ruleDefinition).summary).isNotNull()
    expectThat(rule.apply(resource.withDetail("isValid", true), ruleDefinition).summary).isNull()

    expectThat(rule.apply(resource.withDetail("version", 0), ruleDefinition).summary).isNull()
    expectThat(rule.apply(resource.withDetail("version", 1.0), ruleDefinition).summary).isNotNull()
  }
}
