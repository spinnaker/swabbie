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

package com.netflix.spinnaker.swabbie.aws.provider

import com.netflix.spinnaker.swabbie.provider.SecurityGroupProvider
import com.netflix.spinnaker.swabbie.aws.service.EddaService
import com.netflix.spinnaker.swabbie.model.SecurityGroup
import org.springframework.stereotype.Component

@Component
open class AmazonSecurityGroupProvider(
  private val eddaService: EddaService
): SecurityGroupProvider {
  override fun getSecurityGroups(filters: Map<String, Any>): List<SecurityGroup> {
    //TODO: precalculate edda clients by region. hardcoded to us-east-1, test
    //TODO: need to be able to keep a local cache of resources
    val account = filters["account"]
    val region = filters["region"]
    val ids: String = eddaService.getSecurityGroupIds().first { it == "sg-4fc3253d" }
    return arrayListOf(eddaService.getSecurityGroup(ids)) as List<SecurityGroup>
  }
}
