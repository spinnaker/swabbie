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

package com.netflix.spinnaker.swabbie.aws.securitygroups

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP

@JsonTypeName("amazonSecurityGroup")
data class AmazonSecurityGroup(
  val groupId: String,
  val groupName: String,
  val description: String?,
  val vpcId: String?,
  val ownerId: String,
  override val resourceId: String = groupId,
  override val name: String = groupName,
  override val resourceType: String = SECURITY_GROUP,
  override val cloudProvider: String = AWS,
  private val creationDate: String?
) : AmazonResource(creationDate) {
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}
