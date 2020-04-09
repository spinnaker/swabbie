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

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.DiscoveryClient
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Optional
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.isNull

object NotInDiscoveryRuleTest {
  private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

  @Test
  fun `should apply if server group is out of service discovery`() {
    val discoveryClient = mock<DiscoveryClient>()
    val instanceInfo = mock<InstanceInfo>()
    val instanceId = "i-instanceId"
    val instanceLastUpdatedTimestamp = Instant.now(clock).minus(2, ChronoUnit.DAYS).toEpochMilli()

    whenever(discoveryClient.getInstancesById(instanceId)) doReturn
      listOf(instanceInfo)

    whenever(instanceInfo.status) doReturn
      InstanceInfo.InstanceStatus.OUT_OF_SERVICE

    whenever(instanceInfo.lastUpdatedTimestamp) doReturn instanceLastUpdatedTimestamp

    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(mapOf("instanceId" to instanceId)),
      loadBalancerNames = listOf(),
      createdTime = clock.millis()
    )

    val rule = NotInDiscoveryRule(Optional.of(discoveryClient), clock)

    // applies because asg is out of discovery
    expectThat(rule.apply(asg).summary).isNotNull()

    var ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf("moreThanDays" to 3)
      }

    // doesn't apply because even though the asg is out of discovery, it hasn't been longer than 3 days
    expectThat(rule.apply(asg, ruleDefinition).summary).isNull()

    ruleDefinition = RuleDefinition()
      .apply {
        name = rule.name()
        parameters = mapOf("moreThanDays" to 1)
      }

    // applies because the asg is out of discovery and it has been longer than 3 days
    expectThat(rule.apply(asg, ruleDefinition).summary).isNotNull()
  }
}
