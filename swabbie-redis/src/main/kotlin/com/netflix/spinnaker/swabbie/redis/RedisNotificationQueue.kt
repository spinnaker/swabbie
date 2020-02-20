/*
 * Copyright 2019 Netflix, Inc.
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

import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.NotificationTask
import org.springframework.stereotype.Component

/**
 * Stores notification tasks in a set
 */
@Component
class RedisNotificationQueue(
  private val objectMapper: ObjectMapper,
  redisClientSelector: RedisClientSelector
) : NotificationQueue {
  private val NOTIFICATION_KEY = "{swabbie:notifications}"
  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")

  override fun add(notificationTask: NotificationTask) {
    redisClientDelegate.withCommandsClient { client ->
      client.sadd(NOTIFICATION_KEY, objectMapper.writeValueAsString(notificationTask))
    }
  }

  override fun size(): Int {
    return redisClientDelegate.withCommandsClient<Set<String>> { client ->
      client.smembers(NOTIFICATION_KEY)
    }.size
  }

  override fun popAll(): List<NotificationTask> {
    val json = redisClientDelegate.withCommandsClient<Set<String>> { client ->
      client.spop(NOTIFICATION_KEY, size().toLong())
    } ?: return emptyList()

    return objectMapper.readValue(json.toString())
  }

  override fun isEmpty(): Boolean = size() == 0
}
