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
import com.netflix.spinnaker.swabbie.scheduler.WorkRepository
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class RedisWorkRepository
@Autowired constructor(
  @Qualifier("mainRedisClient") private val mainRedisClientDelegate: RedisClientDelegate,
  @Qualifier("previousRedisClient") private val previousRedisClientDelegate: RedisClientDelegate?,
  private val objectMapper: ObjectMapper
): WorkRepository, RedisClientDelegateSupport(mainRedisClientDelegate, previousRedisClientDelegate) {
  override fun getWork(): List<MarkResourceDescription> {
    allWorkKey().let { key ->
      return getClientForId(key).run {
        this.withCommandsClient<Set<String>> { client ->
          client.smembers(key)
        }.let { set ->
            if (!set.isEmpty()) {
              this.withMultiClient<Set<String>> {client ->
                client.mget(*set.map { workKey(it) }.toTypedArray()).toSet()
              }.map { json ->
                  objectMapper.readValue<MarkResourceDescription>(json)
                }
            } else {
              emptyList()
            }
          }
      }
    }
  }

  override fun createWork(resourceDescription: MarkResourceDescription) {
    allWorkKey().let { key ->
      getClientForId(key).withCommandsClient{ client ->
        client.set(workKey(resourceDescription.namespace), objectMapper.writeValueAsString(resourceDescription))
        client.sadd(key, resourceDescription.namespace)
      }
    }
  }

  override fun remove(resourceDescription: MarkResourceDescription) {
    allWorkKey().let { key ->
      getClientForId(key).withCommandsClient { client ->
        client.srem(key, resourceDescription.namespace)
        client.del(workKey(resourceDescription.namespace))
      }
    }
  }
}

internal fun allWorkKey() = "{swabbie:work}"
internal fun workKey(namespace: String) = "{swabbie:work}:$namespace"
