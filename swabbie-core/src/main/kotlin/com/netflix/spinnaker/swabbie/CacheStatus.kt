package com.netflix.spinnaker.swabbie

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.PreDestroy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

interface CacheStatus {
  fun cachesLoaded(): Boolean
}

@Component
open class InMemoryCacheStatus(
  private val caches: List<Cache<*>>,
  private val singletonCaches: List<SingletonCache<*>>
) : CacheStatus {
  private var allLoaded = AtomicReference<Boolean>(false)
  private val monitorExecutor = Executors.newSingleThreadScheduledExecutor()
  private val cacheLoaderExecutor = Executors.newSingleThreadScheduledExecutor()
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  init {
    refreshCaches()

    // monitor the status of caches. Sets allLoaded=true when all caches are filled.
    monitorCaches()
  }

  private fun monitorCaches() {
    monitorExecutor.scheduleWithFixedDelay(
      {
        try {
          if (!allLoaded.get()) {
            log.debug("All caches not loaded, checking cache status.")
            updateStatus()
          }
        } catch (e: Exception) {
          log.error("Failed while checking the cache status", e)
        }
      },
      0, 1, TimeUnit.MINUTES
    )
  }

  private fun refreshCaches() {
    cacheLoaderExecutor.scheduleWithFixedDelay(
      {
        try {
          caches.forEach(Cache<*>::refresh)
          singletonCaches.forEach(SingletonCache<*>::refresh)
        } catch (e: Exception) {
          log.error("Failed while refreshing caches.", e)
        }
      },
      0, 15, TimeUnit.MINUTES
    )
  }

  private fun updateStatus() {
    caches.forEach { cache ->
      if (!cache.loadingComplete()) return
    }

    singletonCaches.forEach { cache ->
      if (!cache.loadingComplete()) return
    }

    allLoaded.set(true)
  }

  @PreDestroy
  private fun shutdown() {
    monitorExecutor.shutdownNow()
    cacheLoaderExecutor.shutdownNow()
  }

  override fun cachesLoaded(): Boolean {
    return allLoaded.get()
  }
}
