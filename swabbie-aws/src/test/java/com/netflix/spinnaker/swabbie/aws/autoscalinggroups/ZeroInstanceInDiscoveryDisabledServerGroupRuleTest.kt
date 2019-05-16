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

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.DiscoveryClient
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

object ZeroInstanceInDiscoveryDisabledServerGroupRuleTest {
  @Test
  fun `should not apply to non disabled server groups`() {
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234")
      ),
      loadBalancerNames = listOf(),
      createdTime = Instant.now().toEpochMilli()
    ).apply {
      set(IS_DISABLED, false)
    }

    val result = ZeroInstanceInDiscoveryDisabledServerGroupRule(
      Optional.empty()
    ).apply(asg)

    Assertions.assertNull(result.summary)
  }

  @Test
  fun `should apply when server group is disabled with all instances out of discovery`() {
    val discoveryClient = mock<DiscoveryClient>()
    val instanceInfo = mock<InstanceInfo>()

    whenever(discoveryClient.getInstancesById(any())) doReturn
      listOf(instanceInfo)

    whenever(instanceInfo.status) doReturn
      InstanceInfo.InstanceStatus.OUT_OF_SERVICE

    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234")
      ),
      loadBalancerNames = listOf(),
      createdTime = Instant.now().toEpochMilli()
    ).apply {
      set(IS_DISABLED, true)
    }

    val result = ZeroInstanceInDiscoveryDisabledServerGroupRule(
      Optional.of(discoveryClient)
    ).apply(asg)

    Assertions.assertNotNull(result.summary)
  }

  @Test
  fun `should not apply when server group is disabled with some instances out of discovery`() {
    val discoveryClient = mock<DiscoveryClient>()
    val instanceInfoUp = mock<InstanceInfo>()
    val instanceInfoOutOfService = mock<InstanceInfo>()

    whenever(discoveryClient.getInstancesById("i-01234")) doReturn
      listOf(instanceInfoUp)

    whenever(discoveryClient.getInstancesById("i-01235")) doReturn
      listOf(instanceInfoOutOfService)

    whenever(instanceInfoUp.status) doReturn
      InstanceInfo.InstanceStatus.UP

    whenever(instanceInfoOutOfService.status) doReturn
      InstanceInfo.InstanceStatus.OUT_OF_SERVICE

    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234", "InstanceId" to "i-01235")
      ),
      loadBalancerNames = listOf(),
      createdTime = Instant.now().toEpochMilli()
    ).apply {
      set(IS_DISABLED, true)
    }

    val result = ZeroInstanceInDiscoveryDisabledServerGroupRule(
      Optional.of(discoveryClient)
    ).apply(asg)

    Assertions.assertNull(result.summary)
  }
}
