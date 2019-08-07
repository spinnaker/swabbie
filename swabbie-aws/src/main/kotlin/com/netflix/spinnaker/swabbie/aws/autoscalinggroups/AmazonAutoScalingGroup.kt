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

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant

@JsonTypeName("amazonAutoScalingGroup")
data class AmazonAutoScalingGroup(
  val autoScalingGroupName: String,
  val instances: List<Map<String, Any>>?,
  val loadBalancerNames: List<String>?,
  private val createdTime: Long,
  override val resourceId: String = autoScalingGroupName,
  override val resourceType: String = SERVER_GROUP,
  override val cloudProvider: String = AWS,
  override val name: String = autoScalingGroupName,
  private val creationDate: String? = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdTime), ZoneId.systemDefault()).toString()
) : AmazonResource(creationDate) {
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}
