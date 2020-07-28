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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.netflix.spinnaker.config.resourceDeserializerModule
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.notifications.NotificationTask
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import redis.clients.jedis.JedisPool
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import strikt.assertions.size

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
object RedisNotificationQueueTest {
  private val embeddedRedis = EmbeddedRedis.embed()
  private val jedisPool = embeddedRedis.pool as JedisPool
  private val redisClientSelector = RedisClientSelector(
    listOf(JedisClientDelegate("primaryDefault", jedisPool))
  )

  private val objectMapper = ObjectMapper().apply {
    registerModule(KotlinModule())
    registerModule(resourceDeserializerModule())
  }

  private val queue = RedisNotificationQueue(objectMapper, redisClientSelector)

  @BeforeEach
  fun setup() {
    jedisPool.resource.use {
      it.flushDB()
    }
  }

  @AfterAll
  fun cleanup() {
    embeddedRedis.destroy()
  }

  @Test
  fun `should be empty`() {
    assert(queue.isEmpty())
  }

  @Test
  fun `should add`() {
    val workConfiguration = WorkConfigurationTestHelper.generateWorkConfiguration(namespace = "ns1")
    queue.add(
      NotificationTask(
        resourceType = "type1",
        namespace = workConfiguration.namespace
      )
    )

    queue.add(
      NotificationTask(
        resourceType = "type2",
        namespace = workConfiguration.namespace
      )
    )

    expectThat(queue.isEmpty()).isFalse()

    queue.popAll()

    expectThat(queue.isEmpty()).isTrue()

    // should be a set
    queue.add(
      NotificationTask(
        resourceType = "type1",
        namespace = workConfiguration.namespace
      )
    )

    queue.add(
      NotificationTask(
        resourceType = "type1",
        namespace = workConfiguration.namespace
      )
    )

    expectThat(queue.popAll()).size.isEqualTo(1)
  }
}
