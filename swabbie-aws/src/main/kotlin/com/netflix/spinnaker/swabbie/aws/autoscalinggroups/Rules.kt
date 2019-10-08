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
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.Optional

@Component
class ZeroInstanceDisabledServerGroupRule : Rule<AmazonAutoScalingGroup> {

  @Value("\${swabbie.resource.disabled.days.count:30}")
  private val disabledDurationInDays: Long = 30

  override fun apply(resource: AmazonAutoScalingGroup): Result {
    if (!resource.isOutOfLoadBalancer()) {
      return Result(null)
    }

    if (resource.details[HAS_INSTANCES] == null || resource.details[HAS_INSTANCES] == false) {
      val disabledTime = resource.disabledTime() ?: return Result(null)

      // time elapsed since the resource was disabled time is greater than disabledDurationInDays
      if (disabledTime.isBefore(LocalDateTime.now().minusDays(disabledDurationInDays))) {
        return Result(
          Summary(
            description = "Server Group ${resource.autoScalingGroupName} has been disabled for more " +
              "than $disabledDurationInDays days. No active instances.",
            ruleName = javaClass.simpleName
          )
        )
      }
    }

    return Result(null)
  }
}

@Component
class ZeroInstanceInDiscoveryDisabledServerGroupRule(
  private val discoveryClient: Optional<DiscoveryClient>
) : Rule<AmazonAutoScalingGroup> {

  @Value("\${swabbie.resource.disabled.days.count:30}")
  private val disabledDurationInDays: Long = 30

  override fun apply(resource: AmazonAutoScalingGroup): Result {
    if (resource.details[IS_DISABLED] == null || resource.details[IS_DISABLED] == false) {
      return Result(null)
    }
    val disabledTime = resource.disabledTime() ?: return Result(null)
    if (discoveryClient.isPresent) {
      resource.instances?.all {
        discoveryClient.get()
          .getInstancesById(it["instanceId"] as String)
          .all {
            it.status == InstanceInfo.InstanceStatus.OUT_OF_SERVICE
          }
      }?.let { outOfService ->
        if (outOfService && disabledTime.isBefore(LocalDateTime.now().minusDays(disabledDurationInDays))) {
          return Result(
            Summary(
              description = "Server Group ${resource.resourceId} has been disabled for more  " +
                "than $disabledDurationInDays days. All instances out of Service Discovery.",
              ruleName = javaClass.simpleName
            )
          )
        }
      }
    }

    return Result(null)
  }
}

const val HAS_INSTANCES = "hasInstances"
const val IS_DISABLED = "isDisabled"
