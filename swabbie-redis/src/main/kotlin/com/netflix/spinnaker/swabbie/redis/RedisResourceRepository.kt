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
import com.netflix.spinnaker.swabbie.model.TrackedResource
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class RedisResourceRepository
@Autowired constructor(
  @Qualifier("mainRedisClient") private val mainRedisClientDelegate: RedisClientDelegate,
  @Qualifier("previousRedisClient") private val previousRedisClientDelegate: RedisClientDelegate?,
  private val objectMapper: ObjectMapper,
  private val clock: Clock
): ResourceRepository, RedisClientDelegateSupport(mainRedisClientDelegate, previousRedisClientDelegate) {
  override fun getMarkedResources(): List<TrackedResource> {
    resources().let { key ->
      return getClientForId(key).run {
        this.withCommandsClient<Set<String>> { client ->
          client.zrangeByScore(key, "-inf", "+inf")
        }.let { set ->
          when {
            set.isEmpty() -> emptyList()
            else -> this.withCommandsClient<Set<String>> { client ->
              client.hmget(resource(), *set.map { it }.toTypedArray()).toSet()
            }.map { json ->
                objectMapper.readValue<TrackedResource>(json)
              }
          }
        }
      }
    }
  }

  override fun track(trackedResource: TrackedResource, markResourceDescription: MarkResourceDescription) {
    val resourceId = "${markResourceDescription.namespace}:${trackedResource.resourceType}:${trackedResource.resourceId}"
    resource(resourceId).let { key ->
      val score = trackedResource.projectedTerminationTime
      getClientForId(key).withCommandsClient { client ->
        client.hset(resource(), resourceId, objectMapper.writeValueAsString(trackedResource))
        client.zadd(resources(), score.toDouble(), resourceId)
      }
    }
  }

  override fun find(resourceId: String): TrackedResource? {
    resource(resourceId).let { key ->
      val q = getClientForId(key)
        .withCommandsClient<Set<String>> { c ->
          c.zrange(key, 0, clock.millis())
        }

      if (!q.isEmpty()) {
        return objectMapper.readValue(q.first(), TrackedResource::class.java)
      }
    }

    return null
  }

  override fun remove(resourceId: String) {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}

internal fun resource(resourceId: String) = "{swabbie:resource}:$resourceId"
internal fun resource() = "{swabbie:resource}"
internal fun resources() = "{swabbie:resources}"
