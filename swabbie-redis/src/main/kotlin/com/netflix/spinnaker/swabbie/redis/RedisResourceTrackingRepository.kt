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
import com.netflix.spinnaker.config.REDIS_CHUNK_SIZE
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.model.MarkedResource
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
  private val clock: Clock
) : ResourceTrackingRepository {

  private val SINGLE_RESOURCES_KEY = "{swabbie:resource}"
  private val SOFT_DELETE_KEY = "{swabbie:softdeletes}"
  private val DELETE_KEY = "{swabbie:deletes}"

  private val log = LoggerFactory.getLogger(javaClass)
  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun find(resourceId: String, namespace: String): MarkedResource? {
    val key = "$namespace:$resourceId"
    return redisClientDelegate.withCommandsClient<String> { client ->
        client.hget(SINGLE_RESOURCES_KEY, key)
      }?.let { objectMapper.readValue(it) }
  }

  override fun getMarkedResources(): List<MarkedResource> {
    getAllIds(DELETE_KEY, true)
      .let { ids ->
        return hydrateMarkedResources(ids)
      }
  }

  override fun getMarkedResourcesToSoftDelete(): List<MarkedResource> {
    getAllIds(SOFT_DELETE_KEY, false)
      .let { ids ->
        return hydrateMarkedResources(ids)
      }
  }

  override fun getMarkedResourcesToDelete(): List<MarkedResource> {
    getAllIds(DELETE_KEY, false)
      .let { ids ->
        return hydrateMarkedResources(ids)
      }
  }

  override fun getIdsOfMarkedResourcesToSoftDelete(): Set<String> {
    return getAllIds(SOFT_DELETE_KEY, false)
  }

  override fun getIdsOfMarkedResourcesToDelete(): Set<String> {
    return getAllIds(DELETE_KEY, false)
  }

  /**
   * @param includeFutureIds if false, includes only ids that are ready for action.
   *  If true, includes all ids in the sorted set.
   */
  private fun getAllIds(key: String, includeFutureIds: Boolean): Set<String> {
    val results : MutableList<Tuple> = redisClientDelegate.withCommandsClient<MutableList<Tuple>> { client ->
      val results = mutableListOf<Tuple>()
      val scanParams: ScanParams = ScanParams().count(REDIS_CHUNK_SIZE)
      var cursor = "0"
      var shouldContinue = true

      while (shouldContinue) {
        val scanResult: ScanResult<Tuple> = client.zscan(key, cursor, scanParams)
        results.addAll(scanResult.result)
        cursor = scanResult.stringCursor
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

  private fun hydrateMarkedResources(resourseIds: Set<String>): List<MarkedResource> {
    if (resourseIds.isEmpty()) return emptyList()
    return resourseIds.chunked(REDIS_CHUNK_SIZE).map { sublist ->
      return redisClientDelegate.withCommandsClient<Set<String>> { client ->
          client.hmget(SINGLE_RESOURCES_KEY, *sublist.toTypedArray()).toSet()
        }.map { json ->
          objectMapper.readValue<MarkedResource>(json)
        }
    }.flatten()
  }

  override fun upsert(markedResource: MarkedResource, deleteScore: Long, softDeleteScore: Long) {
    val id = markedResource.uniqueId()

    markedResource.apply {
      markTs = if (markTs != null) markTs else Instant.now(clock).toEpochMilli()
      updateTs = if (markTs != null) Instant.now(clock).toEpochMilli() else null
    }

    redisClientDelegate.withCommandsClient { client ->
      client.hset(SINGLE_RESOURCES_KEY, id, objectMapper.writeValueAsString(markedResource))
      client.zadd(DELETE_KEY, deleteScore.toDouble(), id)
      client.zadd(SOFT_DELETE_KEY, softDeleteScore.toDouble(), id)
    }
  }

  override fun setSoftDeleted(markedResource: MarkedResource) {
    val id = markedResource.uniqueId()
    redisClientDelegate.withCommandsClient { client ->
      client.zrem(SOFT_DELETE_KEY, id)
    }
  }

  override fun remove(markedResource: MarkedResource) {
    val id = markedResource.uniqueId()
    redisClientDelegate.withCommandsClient { client ->
      client.zrem(DELETE_KEY, id)
      client.zrem(SOFT_DELETE_KEY, id)
      client.hdel(SINGLE_RESOURCES_KEY, id)
    }
  }
}


