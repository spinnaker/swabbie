/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.swabbie.model.Named
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

interface Cacheable : Named

interface Cache<out T> {
  fun get(): Set<T>
  fun contains(key: String?): Boolean
  fun loadingComplete(): Boolean
}

interface SingletonCache<out T> {
  fun get(): T
  fun loadingComplete(): Boolean
}

open class InMemoryCache<out T : Cacheable>(
  private val sourceProvider: () -> Set<T>
) : Cache<T> {

  val log: Logger = LoggerFactory.getLogger(javaClass)

  override fun contains(key: String?): Boolean {
    if (key == null) return false
    return get().find { it.name == key } != null
  }

  private val cache = AtomicReference<Set<T>>()

  @Scheduled(initialDelay = 0L, fixedDelayString = "\${cache.update-interval-millis:900000}")
  private fun refresh() {
    try {
      log.info("Refreshing cache ${javaClass.name}")
      cache.set(sourceProvider.invoke())
    } catch (e: Exception) {
      log.error("Error refreshing cache ${javaClass.name}", e)
    }
  }

  override fun get(): Set<T> {
    if (cache.get() == null) {
      cache.set(sourceProvider.invoke())
    }

    return cache.get()
  }

  override fun loadingComplete(): Boolean {
    return cache.get() != null
  }
}

open class InMemorySingletonCache<out T : Cacheable>(
  private val sourceProvider: () -> T
) : SingletonCache<T> {
  val log: Logger = LoggerFactory.getLogger(javaClass)

  private val cache = AtomicReference<T>()

  @Scheduled(initialDelay = 0L, fixedDelayString = "\${cache.update-interval-millis:900000}")
  private fun refresh() {
    try {
      log.info("Refreshing cache ${javaClass.name}")
      cache.set(sourceProvider.invoke())
    } catch (e: Exception) {
      log.error("Error refreshing cache ${javaClass.name}", e)
    }
  }

  override fun get(): T {
    if (cache.get() == null) {
      cache.set(sourceProvider.invoke())
    }
    return cache.get()
  }

  override fun loadingComplete(): Boolean {
    return cache.get() != null
  }
}
