package com.netflix.spinnaker.swabbie.edda.providers

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
  @Lazy private val configCache: InMemoryCache<AmazonLaunchConfigurationCache>
) : CachedViewProvider<AmazonLaunchConfigurationCache> {

  /**
   * @param params["region"]: return a Set<AmazonLaunchConfiguration> across all known accounts in region
   */
  override fun getAll(params: Parameters): Set<AmazonLaunchConfiguration> {
    if (params.containsKey("region")) {
      return configCache.get().elementAt(0).configsByRegion[params["region"]] ?: emptySet()
    } else {
      throw IllegalArgumentException("Missing required region parameter")
    }
  }

  /**
   * Returns an epochMs timestamp of the last cache update
   */
  override fun getLastUpdated(): Long {
    return configCache.get().elementAt(0).lastUpdated
  }

  /**
   * @param region: AWS region
   *
   * Returns a map of <K: all ami's referenced by a launch config in region, V: set of launch configs referencing K>
   */
  fun getRefdAmisForRegion(region: String): Map<String, Set<AmazonLaunchConfiguration>> {
    val cache = configCache.get()
    return cache.elementAt(0).refdAmisByRegion[region].orEmpty()
  }

  // TODO("Refactor Cacheable to support singletons instead of just sets")
  fun load(): Set<AmazonLaunchConfigurationCache> {
    val configsByRegion: MutableMap<String, Set<AmazonLaunchConfiguration>> = mutableMapOf()
    val refdAmisByRegion: MutableMap<String, MutableMap<String, MutableSet<AmazonLaunchConfiguration>>> = mutableMapOf()

    val regions = workConfigurations.asSequence()
      .map { it.location }
      .toSet()

    regions.forEach { region: String ->
      val launchConfigs: Set<AmazonLaunchConfiguration> = eddaApiClients
        .filter { region == it.region }
        .flatMap {
          launchConfigurationProvider.getAll(
            Parameters(
              mapOf(
                "region" to region,
                "account" to it.account.accountId!!,
                "environment" to it.account.environment)
            )
          ) ?: emptyList()
        }
        .toSet()

      val refdAmis: MutableMap<String, MutableSet<AmazonLaunchConfiguration>> = mutableMapOf()

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

