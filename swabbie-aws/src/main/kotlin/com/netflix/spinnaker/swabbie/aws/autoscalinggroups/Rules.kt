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
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.Dates
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Optional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ZeroInstanceDisabledServerGroupRule(
  private val clock: Clock
) : Rule {

  @Value("\${swabbie.resource.disabled.days.count:30}")
  private val disabledDurationInDays: Long = 30

  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = AmazonAutoScalingGroup::class.java.isAssignableFrom(clazz)
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
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
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
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

@Component
class ZeroInstanceRule : ServerGroupRule() {
  override fun <T : Resource> applyRule(resource: T, ruleDefinition: RuleDefinition?): Result {
    return apply(resource, ruleDefinition)
  }

  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (resource !is AmazonAutoScalingGroup || !resource.instances.isNullOrEmpty()) {
      return Result(null)
    }

    return Result(Summary(description = "Server Group ${resource.resourceId} has no instances.", ruleName = name()))
  }
}

@Component
class ZeroLoadBalancerRule : ServerGroupRule() {
  override fun <T : Resource> applyRule(resource: T, ruleDefinition: RuleDefinition?): Result {
    return apply(resource, ruleDefinition)
  }

  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (resource !is AmazonAutoScalingGroup || !resource.loadBalancerNames.isNullOrEmpty()) {
      return Result(null)
    }

    return Result(Summary(description = "Server Group ${resource.resourceId} has no load balancer.", ruleName = name()))
  }
}

@Component
class DisabledLoadBalancerRule(
  private val clock: Clock
) : ServerGroupRule() {
  override fun <T : Resource> applyRule(resource: T, ruleDefinition: RuleDefinition?): Result {
    return apply(resource, ruleDefinition)
  }

  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (resource !is AmazonAutoScalingGroup || resource.isInLoadBalancer() || resource.disabledTime() == null) {
      return Result(null)
    }

    val disabledDays = Duration
      .between(Dates.toInstant(resource.disabledTime()!!), Instant.now(clock))
      .toDays()

    // Parameter moreThanDays is optional. This rule would enforce moreThanDays if present otherwise it just asserts if the server group is disabled
    val moreThanDays = ruleDefinition?.parameters?.get("moreThanDays") as? Int ?: return Result(
      Summary(description = "Server Group ${resource.resourceId} is out of load balancer.", ruleName = name())
    )

    if (disabledDays <= moreThanDays) {
      return Result(null)
    }

    return Result(
      Summary(description = "Server Group ${resource.resourceId} has been out of balancer longer than $moreThanDays days.", ruleName = name())
    )
  }
}

@Component
class NotInDiscoveryRule(
  private val discoveryClient: Optional<DiscoveryClient>,
  private val clock: Clock
) : ServerGroupRule() {
  override fun <T : Resource> applyRule(resource: T, ruleDefinition: RuleDefinition?): Result {
    return apply(resource, ruleDefinition)
  }

  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    // Parameter moreThanDays is optional. This rule would enforce moreThanDays if present otherwise it just asserts
    // if the server group is out of discovery
    val moreThanDays = ruleDefinition?.parameters?.get("moreThanDays") as? Int
    if (resource !is AmazonAutoScalingGroup || !discoveryClient.isPresent || !resource.isOutOfDiscovery(moreThanDays)) {
      return Result(null)
    }

    val violation = if (moreThanDays == null) {
      "Server Group ${resource.resourceId} is out of Service Discovery."
    } else {
      "Server Group ${resource.resourceId} has been out of Service Discovery for more than $moreThanDays days."
    }

    return Result(Summary(violation, name()))
  }

  /**
   * Returns true if all instances are out of discovery
   * @param thresholdDays optional threshold for how many days this server group is out of service.
   * return true if all instances are out of discovery and for over thresholdDays (if thresholdDays != null)
   */
  private fun AmazonAutoScalingGroup.isOutOfDiscovery(thresholdDays: Int?): Boolean {
    return instances?.all {
      val instanceInfos = discoveryClient
        .get()
        .getInstancesById(it["instanceId"] as String)

      instanceInfos.all { instanceInfo ->
        val outOfService = instanceInfo.status == OUT_OF_SERVICE
        val disabledDays = Duration
          .between(Instant.ofEpochMilli(instanceInfo.lastUpdatedTimestamp), Instant.now(clock))
          .toDays()

        if (thresholdDays != null) {
          outOfService && disabledDays > thresholdDays
        } else {
          outOfService
        }
      }
    } ?: false
  }
}

abstract class ServerGroupRule : Rule {
  abstract fun <T : Resource> applyRule(resource: T, ruleDefinition: RuleDefinition?): Result
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    return applyRule(resource, ruleDefinition)
  }

  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean {
    return AmazonAutoScalingGroup::class.java.isAssignableFrom(clazz)
  }
}
