/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.redis

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.config.resourceDeserializerModule
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.kork.test.time.MutableClock
import com.netflix.spinnaker.swabbie.test.TestResource
import java.time.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.util.Assert
import redis.clients.jedis.JedisPool

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
object RedisResourceUseTrackingRepositoryTest {

  private val embeddedRedis = EmbeddedRedis.embed()
  private val jedisPool = embeddedRedis.pool as JedisPool
  private val objectMapper = ObjectMapper().apply {
    registerSubtypes(TestResource::class.java)
    registerModule(KotlinModule())
    registerModule(resourceDeserializerModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  }

  private val clock = MutableClock()

  private val resourceUseTrackingRepository = RedisResourceUseTrackingRepository(
    RedisClientSelector(listOf(JedisClientDelegate("primaryDefault", jedisPool))),
    objectMapper,
    clock,
    NoopRegistry(),
    SwabbieProperties().apply { outOfUseThresholdDays = 3 }
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
  fun `adds a resource correctly`() {
    resourceUseTrackingRepository.recordUse("ami-111", "servergroup-v001")
    Assert.isTrue(!resourceUseTrackingRepository.isUnused("ami-111"), "ami was just seen, it shouldn't be unused")
  }

  @Test
  fun `correctly returns unused resource after 5 days`() {
    resourceUseTrackingRepository.recordUse("ami-111", "servergroup-v001")
    clock.incrementBy(Duration.ofDays(2))
    resourceUseTrackingRepository.recordUse("ami-222", "anothersg-v004")
    clock.incrementBy(Duration.ofDays(2))

    val unused = resourceUseTrackingRepository.getUnused()
    Assert.notEmpty(unused, "ami should be unused after 5 days")
    Assert.isTrue(
      unused.first().usedByResourceId == "servergroup-v001",
      "should be the correct server group"
    )
  }

  @Test
  fun `returns null when no last seen info`() {
    val info = resourceUseTrackingRepository.getLastSeenInfo("bla")
    Assert.isNull(info, "resource is not tracked and shouldn't return info")
  }

  @Test
  fun `returns list of in use resources`() {
    resourceUseTrackingRepository.recordUse("ami-111", "servergroup-v001")
    clock.incrementBy(Duration.ofDays(2))
    resourceUseTrackingRepository.recordUse("ami-222", "anothersg-v004")
    clock.incrementBy(Duration.ofDays(2))

    val used = resourceUseTrackingRepository.getUsed()
    Assert.notEmpty(used, "one resource should be in use")
    Assert.isTrue(
      used.first().equals("ami-222"),
      "Resource has not yet met the out of use threshold number of days not seen"
    )
  }
}
