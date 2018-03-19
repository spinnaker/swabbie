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

package com.netflix.spinnaker.swabbie.work

import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.config.mergeExclusions
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.exclusions.ExclusionPolicy
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.model.EmptyAccount
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class WorkConfigurator(
  private val swabbieProperties: SwabbieProperties,
  private val accountProvider: AccountProvider,
  private val exclusionPolicies: List<ExclusionPolicy>
) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  private fun getAccounts(accountProvider: AccountProvider): List<Account> {
    accountProvider.getAccounts().let { accounts ->
      return if (accounts.isEmpty()) {
        listOf(EmptyAccount())
      } else {
        accounts.toList()
      }
    }
  }

  fun generateWorkConfigurations(): List<WorkConfiguration> {
    val all = mutableListOf<WorkConfiguration>()
    val spinnakerAccounts = getAccounts(accountProvider)
    log.info("Loading Swabbie configuration {}", swabbieProperties)
    swabbieProperties.providers.forEach { cloudProviderConfiguration ->
      cloudProviderConfiguration.resourceTypes.filter {
        it.enabled
      }.forEach { resourceTypeConfiguration ->
          spinnakerAccounts.filter {
            cloudProviderConfiguration.accounts.contains(it.name) && it.type.equals(cloudProviderConfiguration.name, ignoreCase = true)
          }.forEach { account ->
              cloudProviderConfiguration.locations.forEach { location ->
                "${cloudProviderConfiguration.name}:${account.name}:$location:${resourceTypeConfiguration.name}".let { namespace ->
                  WorkConfiguration(
                    namespace = namespace.toLowerCase(),
                    account = account,
                    location = location,
                    cloudProvider = cloudProviderConfiguration.name,
                    resourceType = resourceTypeConfiguration.name,
                    retentionDays = resourceTypeConfiguration.retentionDays,
                    exclusions = mergeExclusions(cloudProviderConfiguration.exclusions, resourceTypeConfiguration.exclusions),
                    dryRun = if (swabbieProperties.dryRun) true else (resourceTypeConfiguration.dryRun || swabbieProperties.dryRun)
                  ).let { configuration ->
                    configuration.takeIf {
                      !account.shouldBeExcluded(exclusionPolicies, it.exclusions)
                    }?.let {
                        all.add(configuration)
                      }
                  }
                }
              }
            }
        }
    }

    log.info("Generated work configurations {}", all)
    return all
  }
}
