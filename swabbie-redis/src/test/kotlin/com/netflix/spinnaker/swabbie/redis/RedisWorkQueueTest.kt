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

import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import redis.clients.jedis.JedisPool

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
object RedisWorkQueueTest {
  private val embeddedRedis = EmbeddedRedis.embed()
  private val jedisPool = embeddedRedis.pool as JedisPool
  private val workConfiguration1 = WorkConfiguration(
    namespace = "workConfiguration1",
    account = SpinnakerAccount(
      name = "test",
      accountId = "id",
      type = "type",
      edda = "",
      regions = emptyList(),
      eddaEnabled = false,
      environment = "test"
    ),
    location = "us-east-1",
    cloudProvider = AWS,
    resourceType = "testResourceType",
    retention = 14,
    exclusions = emptySet(),
    maxAge = 1
  )

  private val workConfiguration2 = WorkConfiguration(
    namespace = "workConfiguration2",
    account = SpinnakerAccount(
      name = "test",
      accountId = "id",
      type = "type",
      edda = "",
      regions = emptyList(),
      eddaEnabled = false,
      environment = "test"
    ),
    location = "us-east-1",
    cloudProvider = AWS,
    resourceType = "testResourceType",
    retention = 14,
    exclusions = emptySet(),
    maxAge = 1
  )

  private val queue = RedisWorkQueue(
    redisClientSelector = RedisClientSelector(listOf(JedisClientDelegate("primaryDefault", jedisPool))),
    _seed = listOf(workConfiguration1, workConfiguration2)
  )

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
  fun `should seed the queue`() {
    assert(queue.isEmpty())
    queue.seed()
    assert(!queue.isEmpty())
  }

  @Test
  fun `should perform inserts, deletes`() {
    assert(queue.isEmpty())
    // generate work items of MARK, DELETE, NOTIFY
    workConfiguration2.toWorkItems().forEach {
      queue.push(it)
    }

    // generate work items of MARK, DELETE, NOTIFY
    workConfiguration1.toWorkItems().forEach {
      queue.push(it)
    }

    while (!queue.isEmpty()) {
      queue.pop()
    }

    assert(queue.isEmpty())
  }
}
