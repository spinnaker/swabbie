package com.netflix.spinnaker.swabbie

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

interface CacheStatus {
  abstract fun cachesLoaded(): Boolean
}

@Component
open class InMemoryCacheStatus(
  private val caches: List<Cache<*>>,
  private val singletonCaches: List<SingletonCache<*>>
) : CacheStatus {
  private var allLoaded = AtomicReference<Boolean>(false)
  private val executorService = Executors.newSingleThreadScheduledExecutor()
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  init {
    executorService.scheduleWithFixedDelay(
      {
        try {
          if (!allLoaded.get()) {
            log.debug("All caches not loaded, checking cache status.")
            updateStatus()
          } else {
            log.debug("All caches loaded")
            shutdown()
          }
        } catch (e: Exception) {
          log.error("Failed while checking the caches in ${javaClass.simpleName}.", e)
        }
      },
      0, 5, TimeUnit.SECONDS
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

  private fun shutdown() {
    executorService.shutdown()
    try {
      if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
        executorService.shutdownNow()
      }
    } catch (e: InterruptedException) {
      executorService.shutdownNow()
    }
  }

  override fun cachesLoaded(): Boolean {
    return allLoaded.get()
  }
}
