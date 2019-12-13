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

package com.netflix.spinnaker.swabbie.aws.autoscalinggroups

import com.amazonaws.services.autoscaling.model.SuspendedProcess
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.kork.test.time.MutableClock
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import java.time.Duration
import java.time.LocalDateTime

object DisabledLoadBalancerRuleTest {
  private val clock = MutableClock()

  @Test
  fun `should apply when server group's load balancer has been suspended`() {
    val disabledTime = LocalDateTime.now(clock)
    val suspendedProcess = SuspendedProcess()
      .withProcessName("AddToLoadBalancer")
      .withSuspensionReason("User suspended at $disabledTime")

    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(),
      loadBalancerNames = listOf(),
      suspendedProcesses = listOf(
        suspendedProcess
      ),
      createdTime = clock.millis()
    )

    clock.incrementBy(Duration.ofDays(2))
    val rule = DisabledLoadBalancerRule(clock)

    expectThat(rule.apply(asg).summary).isNotNull()

    var ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf("moreThanDays" to 3)
      }

    expectThat(rule.apply(asg, ruleDefinition).summary).isNull()

    ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf("moreThanDays" to 1)
      }

    expectThat(rule.apply(asg, ruleDefinition).summary).isNotNull()

    ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
      }

    // When no parameter passed, rule should still assert that resource is disabled
    expectThat(rule.apply(asg, ruleDefinition).summary).isNotNull()
    expectThat(rule.apply(asg.copy(suspendedProcesses = emptyList()), ruleDefinition).summary).isNull()
  }
}
