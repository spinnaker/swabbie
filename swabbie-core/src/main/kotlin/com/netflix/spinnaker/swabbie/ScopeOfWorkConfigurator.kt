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

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.Retention
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.config.mergeExclusions
import com.netflix.spinnaker.swabbie.provider.AccountProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component


@Component
@EnableConfigurationProperties(SwabbieProperties::class)
class ScopeOfWorkConfigurator(
  private val swabbieProperties: SwabbieProperties,
  private val accountProvider: AccountProvider
) {
  private var _scopeOfWorkConfigurations = mutableListOf<ScopeOfWork>()
  fun list(): MutableList<ScopeOfWork> {
    return _scopeOfWorkConfigurations
  }

  /**
   * Configuration namespace/id format is {cloudProvider}:{account}:{location}:{resourceType}
   * locations in aws are regions
   */
  init {
    val globalExclusions: MutableList<Exclusion>? = swabbieProperties.globalExclusions
    swabbieProperties.providers.forEach { cloudProviderConfiguration ->
      cloudProviderConfiguration.resourceTypes.forEach { resourceTypeConfiguration ->
        accountProvider.getAccounts().forEach { account ->
          cloudProviderConfiguration.locations.forEach { location ->
            val configurationId = "${cloudProviderConfiguration.name}:$account:$location:${resourceTypeConfiguration.name}"
            _scopeOfWorkConfigurations.add(
              ScopeOfWork(
                configurationId,
                ScopeOfWorkConfiguration(
                  configurationId = configurationId,
                  account = account,
                  location = location,
                  cloudProvider = cloudProviderConfiguration.name,
                  resourceType = resourceTypeConfiguration.name,
                  retention = resourceTypeConfiguration.retention,
                  exclusions = mergeExclusions(globalExclusions, resourceTypeConfiguration.exclusions),
                  dryRun = resourceTypeConfiguration.dryRun
                )
              )
            )
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
  val configurationId: String,
  val account: String,
  val location: String,
  val cloudProvider: String,
  val resourceType: String,
  val retention: Retention,
  val exclusions: List<Exclusion>,
  val dryRun: Boolean = true
)
