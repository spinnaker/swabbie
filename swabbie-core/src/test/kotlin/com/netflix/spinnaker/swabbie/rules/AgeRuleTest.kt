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
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.isNull

object AgeRuleTest {
  private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

  @Test
  fun `should apply if resource is older than moreThanDays`() {
    val createdAt = clock.instant().minus(5, ChronoUnit.DAYS)
    val resource = TestResource(resourceId = "1", createTs = createdAt.toEpochMilli())

    val rule = AgeRule(clock)
    // doesn't apply because the parameter was not provided
    expectThat(rule.apply(resource).summary).isNull()

    var ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf("moreThanDays" to 6)
      }

    // doesn't apply because even though the parameter was provided, the resource isn't older than 6 days
    expectThat(rule.apply(resource, ruleDefinition).summary).isNull()

    // applies because the parameter was provided and the resource is older than 4 days
    ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf("moreThanDays" to 4)
      }

    expectThat(rule.apply(resource, ruleDefinition).summary).isNotNull()
  }
}
