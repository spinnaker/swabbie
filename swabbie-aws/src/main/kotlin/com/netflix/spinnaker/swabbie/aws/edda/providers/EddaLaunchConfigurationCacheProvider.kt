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

package com.netflix.spinnaker.swabbie.aws.edda.providers

import com.netflix.spinnaker.config.EddaApiClient
import com.netflix.spinnaker.swabbie.CachedViewProvider
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.ResourceProvider
import com.netflix.spinnaker.swabbie.aws.caches.AmazonLaunchConfigurationCache
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock

class EddaLaunchConfigurationCacheProvider(
  private val clock: Clock,
  private val workConfigurations: List<WorkConfiguration>,
  private val launchConfigurationProvider: ResourceProvider<AmazonLaunchConfiguration>,
  private val eddaApiClients: List<EddaApiClient>
) : CachedViewProvider<AmazonLaunchConfigurationCache> {

  private val log: Logger = LoggerFactory.getLogger(javaClass)

  override fun load(): AmazonLaunchConfigurationCache {
    log.info("Loading cache for ${javaClass.simpleName}")
    val refdAmisByRegion = mutableMapOf<String, MutableMap<String, MutableSet<AmazonLaunchConfiguration>>>()

    val regions = workConfigurations.asSequence()
      .map { it.location }
      .toSet()

    regions.forEach { region: String ->
      val launchConfigs: Set<AmazonLaunchConfiguration> = eddaApiClients
        .filter { region == it.region }
        .flatMap { edda ->
          launchConfigurationProvider.getAll(
            Parameters(
              region = region,
              account = edda.account.accountId!!,
              environment = edda.account.environment
            )
          ) ?: emptyList()
        }
        .toSet()

      val refdAmis = mutableMapOf<String, MutableSet<AmazonLaunchConfiguration>>()

      launchConfigs.forEach {
        refdAmis.getOrPut(it.imageId) { mutableSetOf() }.add(it)
      }

      refdAmisByRegion[region] = refdAmis
    }
    return AmazonLaunchConfigurationCache(refdAmisByRegion, clock.millis(), "default")
  }
}
