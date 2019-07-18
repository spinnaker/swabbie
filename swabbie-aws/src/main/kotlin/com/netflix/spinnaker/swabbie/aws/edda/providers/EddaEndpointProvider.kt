package com.netflix.spinnaker.swabbie.aws.edda.providers

import com.netflix.spinnaker.swabbie.EndpointProvider
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.EddaEndpoint

class EddaEndpointProvider(
  private val endpointCache: InMemoryCache<EddaEndpoint>
) : EndpointProvider {
  override fun getEndpoints(): Set<EddaEndpoint> {
    return endpointCache.get()
  }
}
