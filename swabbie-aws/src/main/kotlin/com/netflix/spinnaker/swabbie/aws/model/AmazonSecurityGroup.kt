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

package com.netflix.spinnaker.swabbie.aws.model

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP

@JsonTypeName("amazonSecurityGroup")
data class AmazonSecurityGroup
(
  val groupId: String,
  val groupName: String,
  val description: String?,
  val vpcId: String,
  val ownerId: String,
  val ipPermissionsEgress: List<IpPermissionsEgress>?,
  val ipPermissions: List<IpPermission>?,
  val tags: List<Tag>?,
  override val resourceId: String = groupId,
  override val resourceType: String = SECURITY_GROUP,
  override val cloudProvider: String = "aws"
): Resource()

data class IpPermissionsEgress(
  val ipProtocol: String,
  val ipV4Ranges: List<IpV4Range>?,
  val ipV6Ranges: List<IpV6Range>?,
  val userIdGroupPairs: List<UserIdGroupPair>?,
  val prefixListIds: List<String>?
)

data class IpV6Range(
  val cidrIpV6: String,
  val description: String?
)

data class IpV4Range(
  val cidrIpV4: String,
  val description: String?
)

data class IpPermission(
  val ipProtocol: String,
  val fromPort: Int?,
  val toPort: Int?,
  val ipV4Ranges: List<IpV4Range>?,
  val ipV6Ranges: List<IpV6Range>?,
  val userIdGroupPairs: List<UserIdGroupPair>?
)

data class UserIdGroupPair(
  val userId: String,
  val groupId: String
)

data class Tag(
  val key: String,
  val value: String
)
