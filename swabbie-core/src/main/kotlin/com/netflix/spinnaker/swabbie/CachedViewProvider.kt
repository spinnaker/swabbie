package com.netflix.spinnaker.swabbie

interface CachedViewProvider<out T> {
  fun load(): T
}
