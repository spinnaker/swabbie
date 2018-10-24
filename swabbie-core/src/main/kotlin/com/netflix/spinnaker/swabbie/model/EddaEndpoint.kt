package com.netflix.spinnaker.swabbie.model

import com.netflix.spinnaker.swabbie.Cacheable

data class EddaEndpoint(
  val region: String,
  val accountId: String,
  val environment: String,
  val endpoint: String,
  override val name: String
) : Cacheable
