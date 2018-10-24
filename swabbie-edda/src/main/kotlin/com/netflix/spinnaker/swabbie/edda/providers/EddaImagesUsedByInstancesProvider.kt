package com.netflix.spinnaker.swabbie.edda.providers

import com.netflix.spinnaker.config.EddaApiClient
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.lang.IllegalArgumentException
import java.time.Clock

@Configuration
open class EddaImagesUsedByInstancesProvider(
  private val clock: Clock,
  private val workConfigurations: List<WorkConfiguration>,
  private val instanceProvider: ResourceProvider<AmazonInstance>,
  private val eddaApiClients: List<EddaApiClient>,
  @Lazy private val imagesUsedByInstancesCache: InMemoryCache<AmazonImagesUsedByInstancesCache>
) : CachedViewProvider<AmazonImagesUsedByInstancesCache> {

  /**
   * @param params["region"]: return a Set<String> of Amazon imageIds (i.e. "ami-abc123")
   * currently referenced by running EC2 instances in the region
   */
  override fun getAll(params: Parameters): Set<String> {
    if (params.containsKey("region")) {
      return imagesUsedByInstancesCache.get().elementAt(0).refdAmisByRegion[params["region"]] ?: emptySet()
    } else {
      throw IllegalArgumentException("Missing required region parameter")
    }
  }

  /**
   * Returns an epochMs timestamp of the last cache update
   */
  override fun getLastUpdated(): Long {
    return imagesUsedByInstancesCache.get().elementAt(0).lastUpdated
  }

  fun load(): Set<AmazonImagesUsedByInstancesCache> {
    val refdAmisByRegion = mutableMapOf<String, Set<String>>()

    val regions = workConfigurations.asSequence()
      .map { it.location }
      .toSet()

    regions.forEach { region: String ->
      val instances: Set<AmazonInstance> = eddaApiClients
        .filter { region == it.region }
        .flatMap { edda ->
          instanceProvider.getAll(
            Parameters(
              mapOf(
                "region" to region,
                "account" to edda.account.accountId!!,
                "environment" to edda.account.environment
              )
            )
          ) ?: emptyList()
        }
        .toSet()

      val refdAmis: Set<String> = instances.asSequence()
        .map { it.imageId }
        .toSet()

      refdAmisByRegion[region] = refdAmis
    }

    return setOf(
      AmazonImagesUsedByInstancesCache(
        refdAmisByRegion,
        clock.millis(),
        "default"
      )
    )
  }
}


@Configuration
open class EddaImagesUsedByInstancesCache(
  eddaImagesUsedByInstancesProvider: EddaImagesUsedByInstancesProvider
) : InMemoryCache<AmazonImagesUsedByInstancesCache>(eddaImagesUsedByInstancesProvider.load())

data class AmazonImagesUsedByInstancesCache(
  val refdAmisByRegion: Map<String, Set<String>>,
  val lastUpdated: Long,
  override val name: String?
) : Cacheable
