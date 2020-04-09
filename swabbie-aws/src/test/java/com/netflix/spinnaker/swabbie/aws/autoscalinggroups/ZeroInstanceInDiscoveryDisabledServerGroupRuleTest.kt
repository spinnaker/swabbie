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
import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.DiscoveryClient
import com.nhaarman.mockito_kotlin.any
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

object ZeroInstanceInDiscoveryDisabledServerGroupRuleTest {

  private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

  @Test
  fun `should not apply to server groups with instances`() {
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234")
      ),
      loadBalancerNames = listOf(),
      createdTime = clock.millis()
    )

    val result = ZeroInstanceInDiscoveryDisabledServerGroupRule(
      Optional.empty(), clock
    ).apply(asg)

    expectThat(result.summary).isNull()
  }

  @Test
  fun `should apply when server group is out of loadbalancer with all instances out of discovery`() {
    val discoveryClient = mock<DiscoveryClient>()
    val instanceInfo = mock<InstanceInfo>()
    val instanceLastUpdatedTimestamp = Instant.now(clock).minus(35, ChronoUnit.DAYS).toEpochMilli()

    whenever(discoveryClient.getInstancesById(any())) doReturn
      listOf(instanceInfo)

    whenever(instanceInfo.status) doReturn
      InstanceInfo.InstanceStatus.OUT_OF_SERVICE

    whenever(instanceInfo.lastUpdatedTimestamp) doReturn instanceLastUpdatedTimestamp

    val suspendedProcess = SuspendedProcess()
      .withProcessName("AddToLoadBalancer")
      .withSuspensionReason("User suspended at 2019-09-03T17:29:07Z")
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(),
      suspendedProcesses = listOf(
        suspendedProcess
      ),
      loadBalancerNames = listOf(),
      createdTime = clock.millis()
    )

    val result = ZeroInstanceInDiscoveryDisabledServerGroupRule(
      Optional.of(discoveryClient), clock
    ).apply(asg)

    expectThat(result.summary).isNotNull()
  }

  @Test
  fun `should not apply when server group is out of loadbalancer with some instances out of discovery`() {
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
      instances = listOf(),
      loadBalancerNames = listOf(),
      createdTime = clock.millis()
    )

    val result = ZeroInstanceInDiscoveryDisabledServerGroupRule(
      Optional.of(discoveryClient), clock
    ).apply(asg)

    expectThat(result.summary).isNull()
  }

  @Test
  fun `should not apply when instance lastupdated time is less than threshold `() {
    val discoveryClient = mock<DiscoveryClient>()
    val instanceInfo = mock<InstanceInfo>()

    val instanceLastUpdatedTimestamp = Instant.now(clock).toEpochMilli()

    whenever(discoveryClient.getInstancesById(any())) doReturn
      listOf(instanceInfo)

    whenever(instanceInfo.status) doReturn
      InstanceInfo.InstanceStatus.OUT_OF_SERVICE

    whenever(instanceInfo.lastUpdatedTimestamp) doReturn instanceLastUpdatedTimestamp

    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(),
      loadBalancerNames = listOf(),
      createdTime = clock.millis()
    )

    val result = ZeroInstanceInDiscoveryDisabledServerGroupRule(
      Optional.of(discoveryClient), clock
    ).apply(asg)

    expectThat(result.summary).isNull()
  }
}
