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
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.swabbie.persistence.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.model.MarkedResource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
class RedisResourceTrackingRepository(
  @Qualifier("mainRedisClient") private val mainRedisClientDelegate: RedisClientDelegate,
  @Qualifier("previousRedisClient") private val previousRedisClientDelegate: RedisClientDelegate?,
  private val objectMapper: ObjectMapper,
  private val clock: Clock
): ResourceTrackingRepository, RedisClientDelegateSupport(mainRedisClientDelegate, previousRedisClientDelegate) {
  override fun getMarkedResources(): List<MarkedResource>? {
    return doGetAll(true)
  }

  override fun getMarkedResourcesToDelete(): List<MarkedResource>? {
    return doGetAll(false)
  }

  private fun doGetAll(includeAll: Boolean): List<MarkedResource>? {
    ALL_RESOURCES_KEY.let { key ->
      return getClientForId(key).run {
        this.withCommandsClient<Set<String>> { client ->
          if (includeAll) {
            client.zrangeByScore(key, "-inf", "+inf")
          } else {
            client.zrangeByScore(key, 0.0, clock.instant().toEpochMilli().toDouble())
          }
        }.let { set ->
            if (set.isEmpty()) emptyList()
            else this.withCommandsClient<Set<String>> { client ->
              client.hmget(SINGLE_RESOURCES_KEY, *set.map { it }.toTypedArray()).toSet()
            }.map { json ->
                objectMapper.readValue<MarkedResource>(json)
              }
          }
      }
    }
  }

  override fun upsert(markedResource: MarkedResource, score: Long) {
    val resourceId = "${markedResource.configurationId}:${markedResource.resourceId}"
    markedResource.apply {
      createdTs = if (createdTs != null) createdTs else Instant.now(clock).toEpochMilli()
      updateTs = Instant.now(clock).toEpochMilli()
    }

    resourceKey(resourceId).let { key ->
      getClientForId(key).withCommandsClient { client ->
        client.hset(SINGLE_RESOURCES_KEY, resourceId, objectMapper.writeValueAsString(markedResource))
        client.zadd(ALL_RESOURCES_KEY, score.toDouble(), resourceId)
      }
    }
  }

  override fun remove(resourceId: String) {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}

const val SINGLE_RESOURCES_KEY = "{swabbie:resource}"
const val ALL_RESOURCES_KEY = "{swabbie:resources}"

fun resourceKey(resourceId: String) = "$SINGLE_RESOURCES_KEY:$resourceId"

