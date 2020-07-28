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

package com.netflix.spinnaker.swabbie.aws.caches

import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.CachedViewProvider
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import java.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LaunchConfigurationCacheProvider(
  private val clock: Clock,
  private val workConfigurations: List<WorkConfiguration>,
  private val accountProvider: AccountProvider,
  private val aws: AWS
) : CachedViewProvider<AmazonLaunchConfigurationCache>, AWS by aws {

  private val log: Logger = LoggerFactory.getLogger(javaClass)

  override fun load(): AmazonLaunchConfigurationCache {
    log.info("Loading cache for ${javaClass.simpleName}")
    val refdAmisByRegion = mutableMapOf<String, MutableMap<String, MutableSet<AmazonLaunchConfiguration>>>()
    val configuredRegions = workConfigurations.map { it.location }.toSet()
    accountProvider.getAccounts()
      .filter(isCorrectCloudProviderAndRegion(configuredRegions))
      .forEach { account ->
        account.regions?.forEach { region ->
          log.info("Reading launch configurations in {}/{}/{}", account.accountId, region.name, account.environment)
          val launchConfigs: Set<AmazonLaunchConfiguration> = getLaunchConfigurations(
            Parameters(
              region = region.name,
              account = account.accountId!!,
              environment = account.environment
            )
          ).toSet()

          val refdAmis = mutableMapOf<String, MutableSet<AmazonLaunchConfiguration>>()
          launchConfigs.forEach {
            refdAmis.getOrPut(it.imageId) { mutableSetOf() }.add(it)
          }

          val currentAmis = refdAmisByRegion.getOrDefault(region.name, mutableMapOf())
          currentAmis.putAll(refdAmis)
          refdAmisByRegion[region.name] = currentAmis
        }
      }

    return AmazonLaunchConfigurationCache(refdAmisByRegion, clock.millis(), "default")
  }

  private fun isCorrectCloudProviderAndRegion(configuredRegions: Set<String>) =
    { account: Account ->
      account.cloudProvider == "aws" && !account.regions.isNullOrEmpty() && account.regions!!.any { it.name in configuredRegions }
    }
}
