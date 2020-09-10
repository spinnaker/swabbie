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

import com.amazonaws.services.autoscaling.model.LaunchTemplateSpecification
import com.amazonaws.services.autoscaling.model.SuspendedProcess
import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.Dates
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import java.lang.Exception
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@JsonTypeName("amazonAutoScalingGroup")
data class AmazonAutoScalingGroup(
  val autoScalingGroupName: String,
  val instances: List<Map<String, Any>>?,
  val loadBalancerNames: List<String>?,
  var suspendedProcesses: List<SuspendedProcess>? = null,
  private val createdTime: Long,
  override val resourceId: String = autoScalingGroupName,
  override val resourceType: String = SERVER_GROUP,
  override val cloudProvider: String = AWS,
  override val name: String = autoScalingGroupName,
  private val creationDate: String? = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdTime), ZoneId.systemDefault()).toString(),
  val launchTemplate: LaunchTemplateSpecification? = null
) : AmazonResource(creationDate) {
  private val suspensionReasonDateRegex = "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})".toRegex()

  /**
   * Example URL template from config:
   * https://spinnaker/#/applications/{{application}}/clusters/serverGroupDetails/{{cloudProvider}}/{{env}}/{{region}}/{{resourceId}}
   */
  override fun resourceUrl(workConfiguration: WorkConfiguration): String {
    return workConfiguration.notificationConfiguration.resourceUrl
      .replace("{{application}}", FriggaReflectiveNamer().deriveMoniker(this).app)
      .replace("{{cloudProvider}}", AWS)
      .replace("{{env}}", workConfiguration.account.environment)
      .replace("{{region}}", workConfiguration.location)
      .replace("{{resourceId}}", resourceId)
  }

  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }

  fun isInLoadBalancer(): Boolean {
    return getAddToLoadBalancerProcess() == null
  }

  private fun getAddToLoadBalancerProcess(): SuspendedProcess? {
    return suspendedProcesses?.find {
      it.processName == "AddToLoadBalancer"
    }
  }

  private fun getLoadBalancerSuspensionReason(): String? {
    return getAddToLoadBalancerProcess()?.suspensionReason
  }

  // TODO :aravindd refactor this method
  // Disabled time is here is provided by AWS
  fun disabledTime(): LocalDateTime? {
    if (isInLoadBalancer()) {
      return null
    }
    val suspensionReason = getLoadBalancerSuspensionReason()
    if (suspensionReason.isNullOrEmpty()) {
      throw Exception("Failed to find the suspension reason ")
    }
    val timeSinceDisabled = suspensionReasonDateRegex.find(suspensionReason)?.value
    return timeSinceDisabled?.let { Dates.toLocalDateTime(timeSinceDisabled) }
  }
}
