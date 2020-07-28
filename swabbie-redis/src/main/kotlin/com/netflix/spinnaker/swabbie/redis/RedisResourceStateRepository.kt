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

package com.netflix.spinnaker.swabbie.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.REDIS_CHUNK_SIZE
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import java.time.Clock
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import redis.clients.jedis.ScanParams

@Component
class RedisResourceStateRepository(
  redisClientSelector: RedisClientSelector,
  private val objectMapper: ObjectMapper,
  private val clock: Clock,
  private val registry: Registry,
  @Value("\${swabbie.state.deleted-retention-days:30}") private val deletedRetentionDays: Long,
  @Value("\${swabbie.state.max-cleaned:500}") private val maxCleaned: Int
) : ResourceStateRepository {

  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val log = LoggerFactory.getLogger(javaClass)
  private val redisErrorCounter = registry.counter(registry.createId("redis.resourceState.errors"))

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun upsert(resourceState: ResourceState): ResourceState {
    val markedResource = resourceState.markedResource
    val uniqueId = "${markedResource.namespace}:${markedResource.resourceId}".toLowerCase()
    redisClientDelegate.run {
      this.withCommandsClient { client ->
        client.hset(SINGLE_STATE_KEY, uniqueId, objectMapper.writeValueAsString(resourceState))
        client.sadd(ALL_STATES_KEY, uniqueId)
      }

      return get(resourceState.markedResource.resourceId, resourceState.markedResource.namespace)
        ?: resourceState
    }
  }

  override fun getAll(): List<ResourceState> {
    return redisClientDelegate.run {
      val set = this.withCommandsClient<Set<String>> { client ->
        val results = mutableListOf<String>()
        val scanParams: ScanParams = ScanParams().count(REDIS_CHUNK_SIZE)
        var cursor = "0"
        var shouldContinue = true

        while (shouldContinue) {
          val scanResult = client.sscan(ALL_STATES_KEY, cursor, scanParams)
          results.addAll(scanResult.result)
          cursor = scanResult.cursor
          if ("0" == cursor) {
            shouldContinue = false
          }
        }
        results.toSet()
      }

      if (set.isEmpty()) {
        emptyList()
      } else {
        val state = mutableSetOf<ResourceState>()
        set.chunked(REDIS_CHUNK_SIZE).forEach { subset ->
          this.withCommandsClient<Set<String>> { client ->
            client.hmget(SINGLE_STATE_KEY, *subset.map { it }.toTypedArray()).toSet()
          }?.mapNotNull { json ->
            readState(json)
          }?.let {
            state.addAll(it)
          }
        }
        state.toList()
      }
    }
  }

  override fun get(resourceId: String, namespace: String): ResourceState? {
    "$namespace:$resourceId".let { key ->
      return redisClientDelegate.run {
        this.withCommandsClient<String> { client ->
          client.hget(SINGLE_STATE_KEY, key)
        }?.let { json ->
          readState(json)
        }
      }
    }
  }

  override fun getByStatus(status: String): List<ResourceState> {
    val all = getAll()
    return if (status.equals("FAILED", ignoreCase = true)) {
      all.filter { resourceState ->
        resourceState.currentStatus?.name?.contains("FAILED", ignoreCase = true) ?: false
      }
    } else {
      all.filter { resourceState ->
        resourceState.currentStatus?.name.equals(status, ignoreCase = true)
      }
    }
  }

  override fun remove(resourceId: String, namespace: String) {
    "$namespace:$resourceId".let { id ->
      redisClientDelegate.withCommandsClient { client ->
        client.hdel(SINGLE_STATE_KEY, id)
        client.srem(ALL_STATES_KEY, id)
      }
    }
  }

  private fun readState(state: String?): ResourceState? {
    if (state == null) {
      return null
    }

    var resourceState: ResourceState? = null
    try {
      resourceState = objectMapper.readValue(state)
    } catch (e: Exception) {
      redisErrorCounter.increment()
      log.error("Exception reading marked resource $state in ${javaClass.simpleName}: ", e)
    }
    return resourceState
  }

  @Scheduled(fixedDelay = 300000L) // initialDelay = 300000L
  private fun clean() {
    cleanDeletedResources()
  }

  /**
   * Removes deleted resources from the repository after [deletedRetentionDays] days
   */
  internal fun cleanDeletedResources() {
    log.info("Cleaning deleted resources from the ${javaClass.simpleName}")
    var numCleaned = 0
    getAll().forEach { resource ->
      if (numCleaned >= maxCleaned) {
        log.info("Short circuiting cleaning of the ${javaClass.simpleName}, cleaned $numCleaned resources")
        return
      }

      if (resource.hasBeenDeleted() && resource.shouldClean()) {
        remove(resource.markedResource.resourceId, resource.markedResource.namespace)
        numCleaned ++
      }
    }
    log.info("Cleaned $numCleaned deleted resources from the ${javaClass.simpleName}")
  }

  private fun ResourceState.hasBeenDeleted(): Boolean = statuses.any { it.name.equals("delete", true) }

  /**
   * Returns true if it was marked more than [deletedRetentionDays] ago
   */
  private fun ResourceState.shouldClean(): Boolean =
    statuses.any { it.name.equals("mark", true) } &&
      statuses.first { it.name.equals("mark", true) }
      .timestamp.plus(TimeUnit.DAYS.toMillis(deletedRetentionDays)) < clock.instant().toEpochMilli()
}

const val SINGLE_STATE_KEY = "{swabbie:resourceState}"
const val ALL_STATES_KEY = "{swabbie:resourceStates}"
