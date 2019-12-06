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

package com.netflix.spinnaker.swabbie.aws

import com.netflix.spinnaker.kork.test.time.MutableClock
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import java.time.Duration
import java.time.Instant

object ExpiredResourceRuleTest {
  private val clock = MutableClock()
  private val subject = ExpiredResourceRule(clock)
  private val now = Instant.now(clock).toEpochMilli()
  private val asg = AmazonAutoScalingGroup(
    autoScalingGroupName = "testapp-v001",
    instances = listOf(
      mapOf("instanceId" to "i-01234")
    ),
    loadBalancerNames = listOf(),
    createdTime = now
  )

  @BeforeEach
  fun setup() {
    asg.set("tags", null)
  }

  @Test
  fun `should not apply if resource is not tagged with a ttl`() {
    expectThat(
      subject.apply(asg).summary
    ).isNull()
  }

  @Test
  fun `should not apply if resource is not expired`() {
    val tags = listOf(
      mapOf("ttl" to "4d")
    )

    asg.set("tags", tags)
    expectThat(
      subject.apply(asg).summary
    ).isNull()
  }

  @Test
  fun `should apply if resource is expired`() {
    val tags = listOf(
      mapOf("ttl" to "2d")
    )

    asg.set("tags", tags)

    clock.incrementBy(Duration.ofDays(3))
    expectThat(
      subject.apply(asg).summary
    ).isNotNull()
  }
}
