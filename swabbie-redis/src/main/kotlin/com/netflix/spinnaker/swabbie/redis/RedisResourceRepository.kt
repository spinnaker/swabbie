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
import com.netflix.spinnaker.swabbie.ResourceRepository
import com.netflix.spinnaker.swabbie.model.MarkedResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
class RedisResourceRepository
@Autowired constructor(
  @Qualifier("mainRedisClient") private val mainRedisClientDelegate: RedisClientDelegate,
  @Qualifier("previousRedisClient") private val previousRedisClientDelegate: RedisClientDelegate?,
  private val objectMapper: ObjectMapper,
  private val clock: Clock
): ResourceRepository, RedisClientDelegateSupport(mainRedisClientDelegate, previousRedisClientDelegate) {
  override fun getMarkedResources(): List<MarkedResource>? {
    return doGetAll(true)
  }

  override fun getMarkedResourcesToDelete(): List<MarkedResource>? {
    return doGetAll(false)
  }

  private fun doGetAll(includeAll: Boolean): List<MarkedResource>? {
    resources().let { key ->
      return getClientForId(key).run {
        this.withCommandsClient<Set<String>> { client ->
          if (includeAll) {
            client.zrangeByScore(key, "-inf", "+inf")
          } else {
            client.zrangeByScore(key, 0.0, clock.instant().toEpochMilli().toDouble())
          }
        }.let { set ->
            when {
              set.isEmpty() -> emptyList()
              else -> this.withCommandsClient<Set<String>> { client ->
                client.hmget(resource(), *set.map { it }.toTypedArray()).toSet()
              }.map { json ->
                  objectMapper.readValue<MarkedResource>(json)
                }
            }
          }
      }
    }
  }

  override fun track(markedResource: MarkedResource) {
    val resourceId = "${markedResource.configurationId}:${markedResource.resourceId}"
    resource(resourceId).let { key ->
      val score = clock.instant().plus(Duration.ofMillis(markedResource.projectedTerminationTime)).toEpochMilli().toDouble()
      getClientForId(key).withCommandsClient { client ->
        client.hset(resource(), resourceId, objectMapper.writeValueAsString(markedResource))
        client.zadd(resources(), score, resourceId)
      }
    }
  }

  override fun remove(resourceId: String) {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}

internal fun resource(resourceId: String) = "{swabbie:resource}:$resourceId"
internal fun resource() = "{swabbie:resource}"
internal fun resources() = "{swabbie:resources}"
