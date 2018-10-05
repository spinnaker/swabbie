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
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.model.ResourceState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RedisResourceStateRepository(
  redisClientSelector: RedisClientSelector,
  private val objectMapper: ObjectMapper
) : ResourceStateRepository {

  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val log = LoggerFactory.getLogger(javaClass)

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun upsert(resourceState: ResourceState) {
    "${resourceState.markedResource.namespace}:${resourceState.markedResource.resourceId}".let { id ->
      statesKey(id).let { key ->
        redisClientDelegate.withCommandsClient { client ->
          client.hset(SINGLE_STATE_KEY, id, objectMapper.writeValueAsString(resourceState))
          client.sadd(ALL_STATES_KEY, id)
        }
      }
    }
  }

  override fun getAll(): List<ResourceState> {
    ALL_STATES_KEY.let { key ->
      return redisClientDelegate.run {
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

  override fun get(resourceId: String, namespace: String): ResourceState? {
    "$namespace:$resourceId".let { key ->
      return redisClientDelegate.run {
        this.withCommandsClient<String> { client ->
          client.hget(SINGLE_STATE_KEY, key)
        }?.let { json ->
            objectMapper.readValue(json, ResourceState::class.java)
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
}

const val SINGLE_STATE_KEY = "{swabbie:resourceState}"
const val ALL_STATES_KEY = "{swabbie:resourceStates}"

fun statesKey(id: String) = "{swabbie:resourceStates}:$id"
