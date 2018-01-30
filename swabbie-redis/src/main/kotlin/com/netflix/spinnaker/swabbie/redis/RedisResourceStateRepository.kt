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
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.persistence.ResourceStateRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class RedisResourceStateRepository(
  @Qualifier("mainRedisClient") private val mainRedisClientDelegate: RedisClientDelegate,
  @Qualifier("previousRedisClient") private val previousRedisClientDelegate: RedisClientDelegate?,
  private val objectMapper: ObjectMapper,
  private val clock: Clock
): ResourceStateRepository, RedisClientDelegateSupport(mainRedisClientDelegate, previousRedisClientDelegate) {
  override fun upsert(resourceState: ResourceState) {
    val id = "${resourceState.markedResource.configurationId}:${resourceState.markedResource.resourceId}"
    statesKey(id).let { key ->
      getClientForId(key).withCommandsClient { client ->
        client.hset(SINGLE_STATE_KEY, id, objectMapper.writeValueAsString(resourceState))
        client.sadd(ALL_STATES_KEY, id)
      }
    }
  }

  override fun getAll(): List<ResourceState>? {
    ALL_STATES_KEY.let { key ->
      return getClientForId(key).run {
        this.withCommandsClient<Set<String>> { client ->
            client.smembers(key)
        }.let { set ->
            if (set.isEmpty()) emptyList()
            else this.withCommandsClient<Set<String>> { client ->
              client.hmget(SINGLE_STATE_KEY, *set.map { it }.toTypedArray()).toSet()
            }.map { json ->
                objectMapper.readValue<ResourceState>(json)
              }
          }
      }
    }
  }

  override fun get(resourceId: String, configurationId: String): ResourceState? {
    statesKey("$configurationId:$resourceId").let { key ->
      return getClientForId(key).run {
        this.withCommandsClient<String> { client ->
          client.hget(key, "$configurationId:$resourceId")
        }.let { json ->
            if (json != null ) {
              objectMapper.readValue(json, ResourceState::class.java)
            } else {
              null
            }
          }
      }
    }
  }
}

const val SINGLE_STATE_KEY = "{swabbie:state}"
const val ALL_STATES_KEY = "{swabbie:states}"

fun statesKey(id: String) = "{swabbie:states}:$id"
