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

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.isNull

object ZeroInstanceRuleTest {
  private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

  @Test
  fun `should apply if server group has no instances`() {
    val rule = ZeroInstanceRule()
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234")
      ),
      loadBalancerNames = listOf(),
      createdTime = clock.millis()
    )

    // doesn't apply because the server group has an instance
    expectThat(rule.apply(asg).summary).isNull()

    // applies because the server group has no instances
    expectThat(
      rule.apply(asg.copy(instances = emptyList())).summary
    ).isNotNull()
  }
}
