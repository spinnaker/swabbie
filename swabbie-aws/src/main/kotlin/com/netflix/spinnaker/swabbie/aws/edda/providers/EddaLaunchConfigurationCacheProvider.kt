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
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.lang.IllegalArgumentException
import java.time.Clock

@Component
open class EddaLaunchConfigurationCacheProvider(
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

@Component
open class EddaLaunchConfigurationCache(
  eddaLaunchConfigurationCacheProvider: EddaLaunchConfigurationCacheProvider
) : InMemorySingletonCache<AmazonLaunchConfigurationCache>(eddaLaunchConfigurationCacheProvider::load)

data class AmazonLaunchConfigurationCache(
  private val refdAmisByRegion: Map<String, Map<String, Set<AmazonLaunchConfiguration>>>,
  private val lastUpdated: Long,
  override val name: String?
) : Cacheable {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  fun getLaunchConfigsByRegionForImage(params: Parameters): Set<AmazonLaunchConfiguration> {
    if (params.region != "" && params.id != "") {
      return getRefdAmisForRegion(params.region).getOrDefault(params.id, emptySet())
    } else {
      throw IllegalArgumentException("Missing required region and id parameters")
    }
  }

  /**
   * @param region: AWS region
   *
   * Returns a map of <K: all ami's referenced by a launch config in region, V: set of launch configs referencing K>
   */
  fun getRefdAmisForRegion(region: String): Map<String, Set<AmazonLaunchConfiguration>> {
    return refdAmisByRegion[region].orEmpty()
  }

  fun getLastUpdated(): Long {
    return lastUpdated
  }
}
