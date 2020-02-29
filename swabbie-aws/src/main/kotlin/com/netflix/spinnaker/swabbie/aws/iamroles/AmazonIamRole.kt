/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.aws.iamroles

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.IAM_ROLE
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@JsonTypeName("amazonIamRole")
data class AmazonIamRole(
  val roleName: String,
  val roleId: String,
  val description: String?,
  override val resourceId: String = roleName,
  private val createDate: Long,
  override val name: String = roleName,
  override val resourceType: String = IAM_ROLE,
  override val cloudProvider: String = AWS,
  private val creationDateStr: String? = LocalDateTime.ofInstant(Instant.ofEpochMilli(createDate), ZoneId.systemDefault()).toString()
) : AmazonResource(creationDateStr) {
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}
