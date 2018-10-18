package com.netflix.spinnaker.swabbie.edda

import retrofit.http.GET

interface EddaEndpointsService {
  @GET("/api/v1/edda/endpoints.json")
  fun getEddaEndpoints(): EddaEndpointsContainer

  data class EddaEndpointsContainer(
    val edda_endpoints: EddaEndpoints
  ) {
    data class EddaEndpoints(
      val by_account: List<String>?,
      val by_cluster: List<String>?
    )

    fun get(): List<String> = edda_endpoints.by_cluster ?: emptyList()
  }
}
