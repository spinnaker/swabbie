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

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

object ZeroInstanceDisabledServerGroupRuleTest {
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

    val result = ZeroInstanceDisabledServerGroupRule().apply(asg)
    Assertions.assertNull(result.summary)
  }

  @Test
  fun `should apply when server group is disabled with no instances`() {
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(),
      loadBalancerNames = listOf(),
      createdTime = Instant.now().toEpochMilli()
    ).apply {
      set(IS_DISABLED, true)
      set(HAS_INSTANCES, false)
    }

    val result = ZeroInstanceDisabledServerGroupRule().apply(asg)
    Assertions.assertNotNull(result.summary)
  }
}
