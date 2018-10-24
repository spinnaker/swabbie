package com.netflix.spinnaker.swabbie

interface CachedViewProvider<out T> {
  /**
   * Gets all resources with [Parameters]
   */
  fun getAll(params: Parameters): Collection<Any>

  fun getLastUpdated(): Long
}
