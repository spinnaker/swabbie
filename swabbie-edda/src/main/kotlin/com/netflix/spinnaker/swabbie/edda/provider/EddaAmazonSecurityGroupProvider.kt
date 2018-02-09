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

package com.netflix.spinnaker.swabbie.edda.provider

import com.netflix.spinnaker.config.EddaClient
import com.netflix.spinnaker.swabbie.provider.SecurityGroupProvider
import com.netflix.spinnaker.swabbie.model.Resource
import org.springframework.stereotype.Component

@Component
open class EddaAmazonSecurityGroupProvider(
  private val eddaClients: List<EddaClient>
): SecurityGroupProvider {
  override fun getSecurityGroup(groupId: String, account: String, region: String): Resource? =
    eddaClients.find { it.region == region && it.account == account }?.get()?.getSecurityGroup(groupId)

  override fun getSecurityGroups(account: String, region: String): List<Resource>? =
    eddaClients.find {
      it.region == region && it.account == account
    }?.get()?.let { eddaClient ->
      //TODO: testing
        eddaClient.getSecurityGroupIds().filter { it == "sg-4fc3253d" || it == "sg-c68bf0bc" }.map { eddaClient.getSecurityGroup(it) } //TODO: expensive, needs a local cache
      }
}
