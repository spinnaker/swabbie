/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.REDIS_CHUNK_SIZE
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.repository.LastSeenInfo
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import redis.clients.jedis.ScanParams
import redis.clients.jedis.ScanResult
import redis.clients.jedis.Tuple
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

@Component
class RedisResourceUseTrackingRepository(
  redisClientSelector: RedisClientSelector,
  private val objectMapper: ObjectMapper,
  private val clock: Clock,
  private val registry: Registry,
  swabbieProperties: SwabbieProperties
) : ResourceUseTrackingRepository {

  private val log = LoggerFactory.getLogger(javaClass)
  private val LAST_SEEN = "{swabbie:resourceUseTracking}:lastseen"
  private val LAST_SEEN_INDEX = "{swabbie:resourceUseTracking}:lastseenindex"
  private val INIT_TIME = "swabbie:inittime"
  private val outOfUseThresholdDays = swabbieProperties.outOfUseThresholdDays

  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val redisErrorCounter = registry.counter(registry.createId("redis.resourceUseTracking.errors"))

  init {
    log.info("Using ${javaClass.simpleName}")

    redisClientDelegate.withCommandsClient { client ->
      client.setnx(INIT_TIME, clock.instant().toEpochMilli().toString())
    }
  }

  override fun hasCompleteData(): Boolean {
    return redisClientDelegate.withCommandsClient<String> { client ->
      client.get(INIT_TIME)
    }.toLong() < clock.instant().minus(Duration.ofDays(outOfUseThresholdDays.toLong())).toEpochMilli()
  }

  override fun recordUse(resourceIdentifier: String, usedByResourceIdentifier: String) {
    val now = clock.instant().toEpochMilli()

    redisClientDelegate.withCommandsClient { client ->
      client.hset(
        LAST_SEEN,
        resourceIdentifier,
        objectMapper.writeValueAsString(
          LastSeenInfo(resourceIdentifier, usedByResourceIdentifier, now)
        )
      )
      // add to sorted set by score
      // score = last_seen timestamp
      // if resource is seen in next X days, resource will be updated
      client.zadd(LAST_SEEN_INDEX, now.toDouble(), resourceIdentifier)
    }
  }

  override fun getUnused(): List<LastSeenInfo> {
    val keys = getWithParams(LAST_SEEN_INDEX, 0.0, minusXdays(outOfUseThresholdDays).toDouble())
    return hydrateLastSeen(keys)
  }

  override fun getUsed(): Set<String> {
    return getWithParams(LAST_SEEN_INDEX, minusXdays(outOfUseThresholdDays).toDouble(), Double.MAX_VALUE)
  }

  private fun getWithParams(key: String, min: Double, max: Double): Set<String> {
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

    return results
      .filter { it.score in min..max }
      .map { it.element }
      .toSet()
  }

  override fun getLastSeenInfo(resourceIdentifier: String): LastSeenInfo? {
    return redisClientDelegate.withCommandsClient<String> { client ->
      client.hget(LAST_SEEN, resourceIdentifier)
    }?.let {
      readLastSeenInfo(it)
    }
  }

  private fun hydrateLastSeen(keys: Set<String>): List<LastSeenInfo> {
    if (keys.isEmpty()) return emptyList()

    val hydratedLastSeen = mutableListOf<LastSeenInfo>()
    keys.chunked(REDIS_CHUNK_SIZE).forEach { sublist ->
      val hydrated = redisClientDelegate.withCommandsClient<Set<String>> { client ->
        client.hmget(LAST_SEEN, *sublist.toTypedArray()).toSet()
      }.mapNotNull { json ->
        readLastSeenInfo(json)
      }
      hydratedLastSeen.addAll(hydrated)
    }
    return hydratedLastSeen
  }

  override fun isUnused(resourceIdentifier: String): Boolean {
    return redisClientDelegate.withCommandsClient<Double> { client ->
      client.zscore(LAST_SEEN_INDEX, resourceIdentifier)
    }.toLong() < minusXdays(outOfUseThresholdDays)
  }

  fun minusXdays(days: Int): Long {
    return clock.instant().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
  }

  private fun readLastSeenInfo(value: String): LastSeenInfo? {
    var info: LastSeenInfo? = null
    try {
      info = objectMapper.readValue(value)
    } catch (e: Exception) {
      redisErrorCounter.increment()
      log.error("Exception reading last seen info $value in ${javaClass.simpleName}: ", e)
    }
    return info
  }
}
