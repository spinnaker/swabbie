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

import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.BasicRule
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import com.netflix.spinnaker.swabbie.test.TestWorkConfigurationFactory
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

object TemporalThresholdRuleTest {
  @Test
  fun `should not apply to resources if a basic rule is not defined`() {
    val configuration = TestWorkConfigurationFactory().get()
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234")
      ),
      loadBalancerNames = listOf(),
      createdTime = Instant.now().toEpochMilli()
    )

    val result = TemporalThresholdRule<AmazonAutoScalingGroup>(listOf(configuration)).apply(asg)
    Assertions.assertNull(result.summary)
  }

  @Test
  fun `should apply to resources if rule matches and resource is expired`() {
    val basicRuleConfig = BasicRule().apply {
      name = "Tag"
      attributes = listOf(
        Attribute()
          .withKey("fooKey")
          .withValue(listOf("fooValue"))
      ) }

    val configuration = TestWorkConfigurationFactory(
      rule = basicRuleConfig,
      cloudProvider = AWS,
      resourceType = SERVER_GROUP
    ).get()
    val asg = AmazonAutoScalingGroup(
      autoScalingGroupName = "testapp-v001",
      instances = listOf(
        mapOf("instanceId" to "i-01234")
      ),
      loadBalancerNames = listOf(),
      createdTime = Instant.now().minus(3, ChronoUnit.DAYS).toEpochMilli()
    ).withDetail(
      name = "tags",
      value = listOf(
        mapOf("fooKey" to "fooValue", "ttl" to "1d")
      )
    ) as AmazonAutoScalingGroup

    val result = TemporalThresholdRule<AmazonAutoScalingGroup>(listOf(configuration)).apply(asg)
    Assertions.assertNotNull(result.summary)
  }
}
