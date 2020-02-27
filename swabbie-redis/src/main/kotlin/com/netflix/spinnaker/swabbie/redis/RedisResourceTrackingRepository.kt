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
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.repository.DeleteInfo
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import redis.clients.jedis.ScanParams
import redis.clients.jedis.ScanResult
import redis.clients.jedis.Tuple
import java.time.Clock
import java.time.Instant

@Component
class RedisResourceTrackingRepository(
  redisClientSelector: RedisClientSelector,
  private val objectMapper: ObjectMapper,
  private val registry: Registry,
  private val clock: Clock
) : ResourceTrackingRepository {

  private val SINGLE_RESOURCES_KEY = "{swabbie:resource}"
  private val DELETE_KEY = "{swabbie:deletes}"
  private val REMOVED_KEY = "{swabbie:removed}"

  private val log = LoggerFactory.getLogger(javaClass)
  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val redisErrorCounter = registry.counter(registry.createId("redis.resourceTracking.errors"))

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun find(resourceId: String, namespace: String): MarkedResource? {
    val key = "$namespace:$resourceId".toLowerCase()
    return redisClientDelegate.withCommandsClient<String> { client ->
        client.hget(SINGLE_RESOURCES_KEY, key)
      }?.let { readMarkedResource(it) }
  }

  override fun getMarkedResources(): List<MarkedResource> {
    getAllIds(DELETE_KEY, true)
      .let { ids ->
        return hydrateMarkedResources(ids)
      }
  }

  override fun getNumMarkedResources(): Long {
    return redisClientDelegate.withCommandsClient<Long> { client ->
      client.zcard(DELETE_KEY)
    }
  }

  override fun getMarkedResourcesToDelete(): List<MarkedResource> {
    getAllIds(DELETE_KEY, false)
      .let { ids ->
        return hydrateMarkedResources(ids)
      }
  }

  override fun getIdsOfMarkedResourcesToDelete(): Set<String> {
    return getAllIds(DELETE_KEY, false)
  }

  /**
   * @param includeFutureIds if false, includes only ids that are ready for action.
   *  If true, includes all ids in the sorted set.
   */
  private fun getAllIds(key: String, includeFutureIds: Boolean): Set<String> {
    val results: MutableList<Tuple> = redisClientDelegate.withCommandsClient<MutableList<Tuple>> { client ->
      val results = mutableListOf<Tuple>()
      val scanParams: ScanParams = ScanParams().count(REDIS_CHUNK_SIZE)
      var cursor = "0"
      var shouldContinue = true

      while (shouldContinue) {
        val scanResult: ScanResult<Tuple> = client.zscan(key, cursor, scanParams)
        results.addAll(scanResult.result)
        cursor = scanResult.cursor
        if ("0" == cursor) {
          shouldContinue = false
        }
      }
      results
    }

    return if (includeFutureIds) {
      results.map { it.element }.toSet()
    } else {
      results
        .filter { it.score >= 0.0 && it.score <= clock.instant().toEpochMilli().toDouble() }
        .map { it.element }
        .toSet()
    }
  }

  private fun hydrateMarkedResources(resourceIds: Set<String>): List<MarkedResource> {
    return resourceIds.chunked(REDIS_CHUNK_SIZE).mapNotNull { subList ->
      redisClientDelegate.withCommandsClient<Set<String>> { client ->
        client.hmget(SINGLE_RESOURCES_KEY, *subList.toTypedArray()).toSet()
      }?.mapNotNull { json ->
        readMarkedResource(json)
      }
    }.flatten()
  }

  override fun upsert(markedResource: MarkedResource, deleteScore: Long) {
    val id = markedResource.uniqueId()

    markedResource.apply {
      markTs = if (markTs != null) markTs else Instant.now(clock).toEpochMilli()
      updateTs = if (markTs != null) Instant.now(clock).toEpochMilli() else null
    }

    redisClientDelegate.withCommandsClient { client ->
      client.hset(SINGLE_RESOURCES_KEY, id, objectMapper.writeValueAsString(markedResource))
      client.zadd(DELETE_KEY, deleteScore.toDouble(), id)
    }
  }

  override fun remove(markedResource: MarkedResource) {
    val id = markedResource.uniqueId()
    redisClientDelegate.withCommandsClient { client ->
      client.sadd(
        REMOVED_KEY,
        objectMapper.writeValueAsString(
          DeleteInfo(markedResource.name.orEmpty(), markedResource.resourceId, markedResource.namespace)
        )
      ) // add to the list of everything we've deleted
      client.zrem(DELETE_KEY, id)
      client.hdel(SINGLE_RESOURCES_KEY, id)
    }
  }

  override fun getDeleted(): List<DeleteInfo> {
    return redisClientDelegate.withCommandsClient<Set<String>> { client ->
      val results = mutableSetOf<String>()
      val scanParams: ScanParams = ScanParams().count(REDIS_CHUNK_SIZE)
      var cursor = "0"
      var shouldContinue = true

      while (shouldContinue) {
        val scanResult = client.sscan(REMOVED_KEY, cursor, scanParams)
        results.addAll(scanResult.result)
        cursor = scanResult.cursor
        if ("0" == cursor) {
          shouldContinue = false
        }
      }
      results
      }.mapNotNull { json ->
        var deleteInfo: DeleteInfo? = null
        try {
          deleteInfo = objectMapper.readValue(json)
        } catch (e: Exception) {
          redisErrorCounter.increment()
          log.error("Exception reading delete info $json in ${javaClass.simpleName}: ", e)
        }
        deleteInfo
      }.toList()
    }

  private fun readMarkedResource(resource: String?): MarkedResource? {
    if (resource == null) {
      return null
    }

    var markedResource: MarkedResource? = null
    try {
      markedResource = objectMapper.readValue(resource)
    } catch (e: Exception) {
      redisErrorCounter.increment()
      log.error("Exception when reading marked resource $resource in ${javaClass.simpleName}: ", e)
    }
    return markedResource
  }
}
