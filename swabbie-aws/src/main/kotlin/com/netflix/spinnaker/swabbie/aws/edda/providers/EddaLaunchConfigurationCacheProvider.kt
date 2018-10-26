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
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.lang.IllegalArgumentException
import java.time.Clock

@Configuration
open class EddaLaunchConfigurationCacheProvider(
  private val clock: Clock,
  private val workConfigurations: List<WorkConfiguration>,
  private val launchConfigurationProvider: ResourceProvider<AmazonLaunchConfiguration>,
  private val eddaApiClients: List<EddaApiClient>,
  @Lazy private val launchConfigCache: InMemoryCache<AmazonLaunchConfigurationCache>
) : CachedViewProvider<AmazonLaunchConfigurationCache> {

  /**
   * @param params["region"]: return a Set<AmazonLaunchConfiguration> across all known accounts in region
   */
  override fun getAll(params: Parameters): Set<AmazonLaunchConfiguration> {
    if (params.region != "") {
      return launchConfigCache.get().elementAt(0).configsByRegion[params.region] ?: emptySet()
    } else {
      throw IllegalArgumentException("Missing required region parameter")
    }
  }

  /**
   * Returns an epochMs timestamp of the last cache update
   */
  override fun getLastUpdated(): Long {
    return launchConfigCache.get().elementAt(0).lastUpdated
  }

  /**
   * @param region: AWS region
   *
   * Returns a map of <K: all ami's referenced by a launch config in region, V: set of launch configs referencing K>
   */
  fun getRefdAmisForRegion(region: String): Map<String, Set<AmazonLaunchConfiguration>> {
    val cache = launchConfigCache.get()
    return cache.elementAt(0).refdAmisByRegion[region].orEmpty()
  }

  // TODO("Refactor Cacheable to support singletons instead of just sets")
  fun load(): Set<AmazonLaunchConfigurationCache> {
    val configsByRegion = mutableMapOf<String, Set<AmazonLaunchConfiguration>>()
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

      configsByRegion[region] = launchConfigs
      refdAmisByRegion[region] = refdAmis
    }

    return setOf(
      AmazonLaunchConfigurationCache(
        configsByRegion,
        refdAmisByRegion,
        clock.millis(),
        "default"
      )
    )
  }
}

@Configuration
open class EddaLaunchConfigurationCache(
  eddaLaunchConfigurationCacheProvider: EddaLaunchConfigurationCacheProvider
) : InMemoryCache<AmazonLaunchConfigurationCache>(eddaLaunchConfigurationCacheProvider.load())

data class AmazonLaunchConfigurationCache(
  val configsByRegion: Map<String, Set<AmazonLaunchConfiguration>>,
  val refdAmisByRegion: Map<String, Map<String, Set<AmazonLaunchConfiguration>>>,
  val lastUpdated: Long,
  override val name: String?
) : Cacheable
