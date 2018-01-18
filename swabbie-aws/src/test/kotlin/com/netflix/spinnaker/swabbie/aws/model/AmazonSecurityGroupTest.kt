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

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Test

object AmazonSecurityGroupTest {
  val objectMapper = ObjectMapper().apply {
    registerSubtypes(AmazonSecurityGroup::class.java)
    registerModule(KotlinModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  }

  @Test
  fun `should successfully serialize and deserialize`() {
    val str = objectMapper.writeValueAsString(
      AmazonSecurityGroup(
      groupId = "groupId",
      groupName = "groupName",
      description = "amazon security group",
      vpcId = "vpcId",
      ownerId = "12345",
      ipPermissionsEgress = null,
      ipPermissions = null,
      tags = null
      )
    )

    objectMapper.readValue(str, AmazonSecurityGroup::class.java)
  }
}
