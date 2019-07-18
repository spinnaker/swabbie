package com.netflix.spinnaker.swabbie.aws.caches

import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.swabbie.Cacheable
import com.netflix.spinnaker.swabbie.CachedViewProvider
import com.netflix.spinnaker.swabbie.InMemorySingletonCache
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
open class AmazonLaunchConfigurationInMemoryCache(
  private val provider: CachedViewProvider<AmazonLaunchConfigurationCache>
) : InMemorySingletonCache<AmazonLaunchConfigurationCache>(
  { AuthenticatedRequest.allowAnonymous(provider::load) }
)

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
