package com.netflix.spinnaker.swabbie.aws.edda.providers

import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.swabbie.EndpointProvider
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.aws.edda.EddaEndpointsService
import com.netflix.spinnaker.swabbie.model.EddaEndpoint
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

@Component
class EddaEndpointProvider(
  private val eddaEndpointsService: EddaEndpointsService,
  @Lazy private val endpointCache: InMemoryCache<EddaEndpoint>
) : EndpointProvider {
  override fun getEndpoints(): Set<EddaEndpoint> {
    return endpointCache.get()
  }

  fun load(): Set<EddaEndpoint> {
    return eddaEndpointsService.getEddaEndpoints().get()
      .asSequence()
      .mapNotNull { buildEndpoint(it) }
      .toSet()
  }

  // i.e. "http://edda-account.region.foo.bar.com",
  private fun buildEndpoint(endpoint: String): EddaEndpoint? {
    val regex = """^https?://edda-([\w\-]+)\.([\w\-]+)\.([\w\-]+)\..*$""".toRegex()
    val match = regex.matchEntire(endpoint) ?: return null
    val (account, region, env) = match.destructured

    return EddaEndpoint(region, account, env, endpoint, "$region-$account-$env")
  }
}

@Component
class EddaEndpointCache(
  eddaEndpointProvider: EddaEndpointProvider
) : InMemoryCache<EddaEndpoint>({ AuthenticatedRequest.allowAnonymous(eddaEndpointProvider::load) })
