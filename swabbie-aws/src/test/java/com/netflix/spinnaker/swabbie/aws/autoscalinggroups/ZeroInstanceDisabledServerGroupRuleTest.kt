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

package com.netflix.spinnaker.swabbie.aws.autoscalinggroups

import com.amazonaws.services.autoscaling.model.SuspendedProcess
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

object ZeroInstanceDisabledServerGroupRuleTest {

  private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

  @Test
  fun `should not apply to non disabled server groups`() {
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234")
      ),
      loadBalancerNames = listOf("lb1"),
      createdTime = clock.millis()
    )

    val result = ZeroInstanceDisabledServerGroupRule(clock).apply(asg)
    Assertions.assertNull(result.summary)
  }

  @Test
  fun `should apply when server group is disabled with no instances`() {
    val suspendedProcess = SuspendedProcess()
    suspendedProcess.withProcessName("AddToLoadBalancer")
    suspendedProcess.withSuspensionReason("User suspended at 2019-09-03T17:29:07Z")
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(),
      loadBalancerNames = listOf(),
      suspendedProcesses = listOf(
        suspendedProcess
      ),
      createdTime = Instant.now(clock).minus(35, ChronoUnit.DAYS).toEpochMilli()
    )

    val result = ZeroInstanceDisabledServerGroupRule(clock).apply(asg)
    Assertions.assertNotNull(result.summary)
  }

  @Test
  fun `should not apply when server group has been disabled for less than maxage `() {
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(),
      loadBalancerNames = listOf(),
      createdTime = Instant.now(clock).minus(35, ChronoUnit.DAYS).toEpochMilli()
    )

    val result = ZeroInstanceDisabledServerGroupRule(clock).apply(asg)
    Assertions.assertNull(result.summary)
  }
}
