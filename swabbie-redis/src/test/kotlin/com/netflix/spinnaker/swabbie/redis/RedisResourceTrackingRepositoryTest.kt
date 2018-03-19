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
import com.netflix.spinnaker.config.resourceDeserializerModule
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.work.WorkConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.util.Assert
import redis.clients.jedis.JedisPool
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@TestInstance(Lifecycle.PER_CLASS)
object RedisResourceTrackingRepositoryTest {
  private val embeddedRedis = EmbeddedRedis.embed()
  private val jedisPool = embeddedRedis.pool as JedisPool
  private val objectMapper = ObjectMapper().apply {
    registerSubtypes(TestResource::class.java)
    registerModule(KotlinModule())
    registerModule(resourceDeserializerModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  }

  private val clock = Clock.systemDefaultZone()
  private val resourceRepository = RedisResourceTrackingRepository(JedisClientDelegate(jedisPool), null, objectMapper, clock)

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
  fun `removing a resource should work`() {
    val configuration = WorkConfiguration(
      namespace = "configId",
      account = SpinnakerAccount(name = "test", accountId = "id", type = "aws"),
      location = "us-east-1",
      resourceType = "testResourceType",
      cloudProvider = AWS,
      retentionDays = 14,
      exclusions = emptyList()
    )

    val markedResource = MarkedResource(
      resource = TestResource("marked resourceHash due for deletion now"),
      summaries = listOf(Summary("invalid resourceHash 1", "rule 1")),
      namespace = configuration.namespace,
      projectedDeletionStamp = 0,
      adjustedDeletionStamp = 0,
      notificationInfo = NotificationInfo(
        notificationStamp = clock.instant().toEpochMilli(),
        recipient = "yolo@netflixcom",
        notificationType = "Email"
      )
    )

    resourceRepository.upsert(markedResource)
    Assert.notEmpty(resourceRepository.getMarkedResources(), "should have inserted resource")

    resourceRepository.remove(markedResource)

    resourceRepository.getMarkedResources().let { result ->
      result?.size shouldMatch equalTo(0)
    }
  }

  @Test
  fun `fetch all tracked resources and resources to delete`() {
    val now = Instant.now(clock)
    val twoDaysFromNow = now.plus(2, ChronoUnit.DAYS)
    val configuration = WorkConfiguration(
      namespace = "configId",
      account = SpinnakerAccount(name = "test", accountId = "id", type = "aws"),
      location = "us-east-1",
      resourceType = "testResourceType",
      cloudProvider = AWS,
      retentionDays = 14,
      exclusions = emptyList()
    )

    listOf(
      MarkedResource(
        resource = TestResource("marked resourceHash due for deletion now"),
        summaries = listOf(Summary("invalid resourceHash 1", "rule 1")),
        namespace = configuration.namespace,
        projectedDeletionStamp = 0,
        adjustedDeletionStamp = 0,
        notificationInfo = NotificationInfo(
          notificationStamp = clock.instant().toEpochMilli(),
          recipient = "yolo@netflixcom",
          notificationType = "Email"
        )
      ),
      MarkedResource(
        resource = TestResource("marked resourceHash not due for deletion 2 seconds later"),
        summaries = listOf(Summary("invalid resourceHash 2", "rule 2")),
        namespace = configuration.namespace,
        projectedDeletionStamp = twoDaysFromNow.toEpochMilli(),
        adjustedDeletionStamp = twoDaysFromNow.toEpochMilli()
      ),
      MarkedResource(
        resource = TestResource("random"),
        summaries = listOf(Summary("invalid resourceHash 3", "rule 3")),
        namespace = configuration.namespace,
        projectedDeletionStamp = twoDaysFromNow.toEpochMilli(),
        adjustedDeletionStamp = twoDaysFromNow.toEpochMilli()
      )
    ).forEach { resource ->
      resourceRepository.upsert(resource)
    }

    resourceRepository.getMarkedResources().let { result ->
      result?.size shouldMatch equalTo(3)
    }

    resourceRepository.getMarkedResourcesToDelete().let { result ->
      result?.size shouldMatch equalTo(1)
    }
  }
}
