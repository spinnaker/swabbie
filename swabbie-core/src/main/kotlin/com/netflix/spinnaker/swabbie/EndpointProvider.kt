package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.swabbie.model.EddaEndpoint

interface EndpointProvider {
  fun getEndpoints(): Set<EddaEndpoint>
}
