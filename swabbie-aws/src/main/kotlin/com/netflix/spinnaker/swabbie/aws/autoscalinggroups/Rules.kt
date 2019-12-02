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
import com.netflix.appinfo.InstanceInfo.InstanceStatus.OUT_OF_SERVICE
import com.netflix.discovery.DiscoveryClient
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Optional

@Component
class ZeroInstanceDisabledServerGroupRule(
  private val clock: Clock
) : Rule {

  @Value("\${swabbie.resource.disabled.days.count:30}")
  private val disabledDurationInDays: Long = 30

  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = AmazonAutoScalingGroup::class.java.isAssignableFrom(clazz)
  override fun <T : Resource> apply(resource: T): Result {
    if (resource !is AmazonAutoScalingGroup || resource.isInLoadBalancer()) {
      return Result(null)
    }

    if (resource.instances.isNullOrEmpty()) {
      val disabledTime = resource.disabledTime() ?: return Result(null)

      // time elapsed since the resource was disabled time is greater than disabledDurationInDays
      if (disabledTime.isBefore(LocalDateTime.now(clock).minusDays(disabledDurationInDays))) {
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
  private val discoveryClient: Optional<DiscoveryClient>,
  private val clock: Clock
) : Rule {

  @Value("\${swabbie.resource.disabled.days.count:30}")
  private val disabledDurationInDays: Long = 30

  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = AmazonAutoScalingGroup::class.java.isAssignableFrom(clazz)
  override fun <T : Resource> apply(resource: T): Result {
    if (resource !is AmazonAutoScalingGroup || resource.isInLoadBalancer()) {
      return Result(null)
    }

    if (discoveryClient.isPresent) {
      resource.instances?.all {
        discoveryClient.get().getInstancesById(it["instanceId"] as String).all(this::hasBeenDisabledForThresholdDays)
      }?.let { outOfService ->
        if (outOfService) {
          return Result(
            Summary(
              description = "Server Group ${resource.resourceId} has been disabled for more " +
                "than $disabledDurationInDays days. All instances out of Service Discovery.",
              ruleName = javaClass.simpleName
            )
          )
        }
      }
    }

    return Result(null)
  }

  // Checks if the instance is out of service and that the time elapsed since its been disabled
  // is greater than the threshold defined swabbie.resource.disabled.days.count:30
  private fun hasBeenDisabledForThresholdDays(instance: InstanceInfo): Boolean {
    val disabledInDays = ChronoUnit.DAYS.between(
      Instant.ofEpochMilli(instance.lastUpdatedTimestamp),
      Instant.now(clock)
    )

    return instance.status == OUT_OF_SERVICE && disabledInDays > disabledDurationInDays
  }
}
