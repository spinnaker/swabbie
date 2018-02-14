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

package com.netflix.spinnaker.swabbie.configuration

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.config.mergeExclusions
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.AccountProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import javax.annotation.PostConstruct

@Component
@EnableConfigurationProperties(SwabbieProperties::class)
class ScopeOfWorkConfigurator(
  private val swabbieProperties: SwabbieProperties,
  private val accountProvider: AccountProvider
) {
  private var _scopeOfWorkConfigurations = mutableListOf<ScopeOfWork>()
  fun list(): List<ScopeOfWork> {
    return _scopeOfWorkConfigurations
  }

  /**
   * Configuration namespace/id format is {cloudProvider}:{account}:{location}:{resourceType}
   * locations in aws are regions
   */

  @PostConstruct
  fun configure() {
    swabbieProperties.providers.forEach { cloudProviderConfiguration ->
      cloudProviderConfiguration.resourceTypes.forEach { resourceTypeConfiguration ->
        accountProvider.getAccounts().filter { it.name == "test" }.forEach { account ->
          cloudProviderConfiguration.locations.forEach { location ->
            "${cloudProviderConfiguration.name}:${account.name}:$location:${resourceTypeConfiguration.name}".let { namespace ->
              _scopeOfWorkConfigurations.add(
                ScopeOfWork(
                  namespace = namespace.toLowerCase(),
                  configuration = ScopeOfWorkConfiguration(
                    namespace = namespace.toLowerCase(),
                    account = account,
                    location = location,
                    cloudProvider = cloudProviderConfiguration.name,
                    resourceType = resourceTypeConfiguration.name,
                    retentionDays = resourceTypeConfiguration.retentionDays,
                    exclusions = mergeExclusions(cloudProviderConfiguration.exclusions, resourceTypeConfiguration.exclusions),
                    dryRun = resourceTypeConfiguration.dryRun
                  )
                )
              )
            }
          }
        }
      }
    }
  }

  fun scopeCount(): Int {
    return _scopeOfWorkConfigurations.size
  }
}

data class ScopeOfWork(
  val namespace: String,
  val configuration: ScopeOfWorkConfiguration
)

data class ScopeOfWorkConfiguration(
  val namespace: String,
  val account: Account,
  val location: String,
  val cloudProvider: String,
  val resourceType: String,
  val retentionDays: Int,
  val exclusions: List<Exclusion>,
  val dryRun: Boolean = true
)
