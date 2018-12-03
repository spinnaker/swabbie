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
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import redis.clients.jedis.ScanParams

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
      return redisClientDelegate.run {
        val set = this.withCommandsClient<Set<String>> { client ->
          val results = mutableListOf<String>()
          val scanParams: ScanParams = ScanParams().count(REDIS_CHUNK_SIZE)
          var cursor = "0"
          var shouldContinue = true

          while (shouldContinue) {
            val scanResult = client.sscan(ALL_STATES_KEY, cursor, scanParams)
            results.addAll(scanResult.result)
            cursor = scanResult.stringCursor
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
            val partialState = this.withCommandsClient<Set<String>> { client ->
              client.hmget(SINGLE_STATE_KEY, *subset.map { it }.toTypedArray()).toSet()
            }.mapNotNull { json ->
              readState(json)
            }
            state.addAll(partialState)
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

  private fun readState(state: String): ResourceState? {
    var resourceState: ResourceState? = null
    try {
      resourceState = objectMapper.readValue(state)
    } catch (e: Exception) {
      log.error("Exception reading marked resource $state in ${javaClass.simpleName}: ", e)
    }
    return resourceState
  }
}

const val SINGLE_STATE_KEY = "{swabbie:resourceState}"
const val ALL_STATES_KEY = "{swabbie:resourceStates}"

fun statesKey(id: String) = "{swabbie:resourceStates}:$id"
