/*
 * Copyright 2020 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.aws.launchtemplates

import com.amazonaws.services.ec2.model.ResponseLaunchTemplateData
import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.LAUNCH_TEMPLATE_VERSION
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@JsonTypeName("amazonLaunchTemplateVersion")
data class AmazonLaunchTemplateVersion(
  private val versionNumber: Long = 0,
  private val launchTemplateId: String? = "",
  private val launchTemplateName: String? = "",
  val launchTemplateData: ResponseLaunchTemplateData,
  private val createdTime: Long,
  override val resourceId: String = launchTemplateId!! + ":" + versionNumber,
  override val resourceType: String = LAUNCH_TEMPLATE_VERSION,
  override val cloudProvider: String = AWS,
  override val name: String = launchTemplateName!! + ":" + versionNumber,
  private val creationDate: String? = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdTime), ZoneId.systemDefault()).toString()
) : AmazonResource(creationDate) {
  fun getAutoscalingGroupName() =
    name.substringBeforeLast("-")
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}
