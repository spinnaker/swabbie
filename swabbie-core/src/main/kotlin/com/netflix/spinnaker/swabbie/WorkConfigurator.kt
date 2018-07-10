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

import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.config.mergeExclusions
import com.netflix.spinnaker.swabbie.exclusions.BasicExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.ExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.shouldExclude
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.model.EmptyAccount
import com.netflix.spinnaker.swabbie.model.NotificationConfiguration
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/*
Flattens the YAML configuration into units of work called [WorkConfiguration]
  swabbie:
    providers:
      - name: aws
        locations:
          - us-east-1
        accounts:
          - test
        exclusions:
          - type: Tag
            attributes:
              - key: expiration_time
                value:
                  - never
                  - pattern:^\d+(d|m|y)$
    resourceTypes:
      - name: securityGroup
        enabled: false
        dryRun: true
        retentionDays: 10
        notifyOwner: true
        exclusions:
          - type: Literal
            attributes:
              - key: name
                value:
                  - nf_infranstructure
                  - nf_datacenter
*/
open class WorkConfigurator(
  private val swabbieProperties: SwabbieProperties,
  private val accountProvider: AccountProvider,
  private val exclusionPolicies: List<BasicExclusionPolicy>
) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  /**
   * Gets a list of [Account] from Spinnaker
   * Clouddriver is the default account provider
   */
  internal fun getAccounts(): List<Account> {
    accountProvider.getAccounts().let { accounts ->
      return if (accounts.isEmpty()) {
        listOf(EmptyAccount())
      } else {
        accounts.toList()
      }
    }
  }

  /**
   * Generates a list of [WorkConfiguration] from the application yml configuration
   * Computes what [WorkConfiguration] needs to be excluded based on a list of [ExclusionPolicy]
   *
   */
  fun generateWorkConfigurations(): List<WorkConfiguration> {
    val all = mutableListOf<WorkConfiguration>()
    val spinnakerAccounts = getAccounts()
    log.info("Loading Swabbie configuration ...")
    swabbieProperties.providers.forEach { cloudProviderConfiguration ->
      cloudProviderConfiguration.resourceTypes.filter {
        it.enabled
      }.forEach { resourceTypeConfiguration ->
        spinnakerAccounts.filter {
          cloudProviderConfiguration.accounts.contains(it.name) &&
            it.type.equals(cloudProviderConfiguration.name, ignoreCase = true)
        }.forEach { account ->
          val accountRegions = account.regions?.filter { !it.deprecated }?.map { it.name } ?: emptyList()
          cloudProviderConfiguration.locations.filter {
            accountRegions.contains(it)
          }.forEach { location ->
            val namespace = String.format("%s:%s:%s:%s",
              cloudProviderConfiguration.name,
              account.name,
              location,
              resourceTypeConfiguration.name
            )

            WorkConfiguration(
              namespace = namespace.toLowerCase(),
              account = account,
              location = location,
              cloudProvider = cloudProviderConfiguration.name,
              resourceType = resourceTypeConfiguration.name,
              retentionDays = resourceTypeConfiguration.retentionDays,
              exclusions = mergeExclusions(
                cloudProviderConfiguration.exclusions,
                resourceTypeConfiguration.exclusions
              ),
              dryRun = if (swabbieProperties.dryRun) true else resourceTypeConfiguration.dryRun,
              notificationConfiguration = NotificationConfiguration(
                notifyOwner = resourceTypeConfiguration.notifyOwner,
                spinnakerResourceUrl = swabbieProperties.spinnakerResourceSearchUrl,
                optOutUrl = swabbieProperties.optOutBaseUrl,
                resourcesPerNotification = cloudProviderConfiguration.resourcesPerNotification
              )
            ).let { configuration ->
              configuration.takeIf {
                !shouldExclude(account, it, exclusionPolicies, log)
              }?.let {
                all.add(configuration)
              }
            }
          }
        }
      }
    }

    log.info("Generated {} work configurations {}", all.size, all)
    return all
  }
}
