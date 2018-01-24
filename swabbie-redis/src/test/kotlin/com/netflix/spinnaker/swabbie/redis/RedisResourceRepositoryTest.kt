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

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription
import com.netflix.spinnaker.swabbie.scheduler.RetentionPolicy
import com.netflix.spinnaker.swabbie.test.TestResource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import redis.clients.jedis.JedisPool
import java.time.Clock

@TestInstance(Lifecycle.PER_CLASS)
object RedisResourceRepositoryTest {
  val embeddedRedis = EmbeddedRedis.embed()
  val jedisPool = embeddedRedis.pool as JedisPool
  val objectMapper = ObjectMapper().apply {
    registerSubtypes(TestResource::class.java)
    registerModule(KotlinModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  }

  val clock = Clock.systemDefaultZone()
  val resourceRepository = RedisResourceRepository(JedisClientDelegate(jedisPool), null, objectMapper, clock)

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
  fun `fetch all tracked resources`() {
    val terminationTime = clock.millis()
    val terminationTime5SecondsLater = clock.millis() + 5000

    resourceRepository.track(
      TrackedResource(
        TestResource("testResource 2"),
        listOf(Summary("delete me 2", "importantRule 2")),
        Notification(clock.millis(), "yolo@netflix.com", "Email"),
        terminationTime5SecondsLater
      ),
      MarkResourceDescription("namespace", "testResourceType", "aws", RetentionPolicy(null, 10))

    )

    resourceRepository.track(
      TrackedResource(
        TestResource("testResource"),
        listOf(Summary("delete me", "importantRule")),
        Notification(clock.millis(), "yolo@netflix.com", "Email"),
        terminationTime
      ),
      MarkResourceDescription("namespace", "testResourceType", "aws", RetentionPolicy(null, 10))
    )

    resourceRepository.getMarkedResources().let { result ->
      result.size shouldMatch equalTo(2)
      result.first().projectedTerminationTime shouldMatch equalTo(terminationTime)
      result.last().projectedTerminationTime shouldMatch equalTo(terminationTime5SecondsLater)
    }
  }
}
