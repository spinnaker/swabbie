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
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.resourceDeserializerModule
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.repository.DeleteInfo
import com.netflix.spinnaker.swabbie.test.TestResource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.util.Assert
import redis.clients.jedis.JedisPool

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

  private val clock = Clock.fixed(Instant.parse("2018-05-24T12:34:56Z"), ZoneOffset.UTC)
  private val resourceRepository = RedisResourceTrackingRepository(
    RedisClientSelector(listOf(JedisClientDelegate("primaryDefault", jedisPool))),
    objectMapper,
    NoopRegistry(),
    clock
  )

  private val defaultMarkedResource = MarkedResource(
    resource = TestResource(resourceId = "test"),
    summaries = listOf(Summary("invalid resourceHash 1", "rule 1")),
    namespace = "namespace",
    projectedDeletionStamp = 1,
    notificationInfo = NotificationInfo(
      recipient = "yolo@netflixcom",
      notificationType = "email",
      notificationStamp = clock.instant().toEpochMilli()
    )
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
  fun `removing a resource should work`() {
    val configuration = WorkConfiguration(
      namespace = "configId",
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

    val markedResource = MarkedResource(
      resource = TestResource("marked resourceHash due for deletion now"),
      summaries = listOf(Summary("invalid resourceHash 1", "rule 1")),
      namespace = configuration.namespace,
      projectedDeletionStamp = 0,
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflixcom",
        notificationType = "email",
        notificationStamp = clock.instant().toEpochMilli()
      )
    )

    resourceRepository.upsert(markedResource)
    Assert.notEmpty(resourceRepository.getMarkedResources(), "should have inserted resource")

    resourceRepository.remove(markedResource)

    resourceRepository.getMarkedResources().let { result ->
      result.size shouldMatch equalTo(0)
    }
  }

  @Test
  fun `fetch all tracked resources and resources to delete`() {
    val now = Instant.now(clock)
    val twoDaysFromNow = now.plus(2, ChronoUnit.DAYS)
    val configuration = WorkConfiguration(
      namespace = "configId",
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

    listOf(
      MarkedResource(
        resource = TestResource(resourceId = "1", name = "marked resourceHash due for deletion now"),
        summaries = listOf(Summary("invalid resourceHash 1", "rule 1")),
        namespace = configuration.namespace,
        projectedDeletionStamp = 0,
        notificationInfo = NotificationInfo(
          recipient = "yolo@netflixcom",
          notificationType = "email",
          notificationStamp = clock.instant().toEpochMilli()
        )
      ),
      MarkedResource(
        resource = TestResource(resourceId = "2", name = "marked resourceHash not due for deletion 2 seconds later"),
        summaries = listOf(Summary("invalid resourceHash 2", "rule 2")),
        namespace = configuration.namespace,
        projectedDeletionStamp = twoDaysFromNow.toEpochMilli()
      ),
      MarkedResource(
        resource = TestResource(resourceId = "3", name = "random"),
        summaries = listOf(Summary("invalid resourceHash 3", "rule 3")),
        namespace = configuration.namespace,
        projectedDeletionStamp = twoDaysFromNow.toEpochMilli()
      )
    ).forEach { resource ->
      resourceRepository.upsert(resource)
    }

    resourceRepository.getMarkedResources().let { result ->
      result.size shouldMatch equalTo(3)
    }

    resourceRepository.getMarkedResourcesToDelete().let { result ->
      result.size shouldMatch equalTo(1)
    }
  }

  @Test
  fun `fetch num resources should work`() {
    val configuration = WorkConfiguration(
      namespace = "configId",
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

    val markedResource = MarkedResource(
      resource = TestResource("marked resourceHash due for deletion now"),
      summaries = listOf(Summary("invalid resourceHash 1", "rule 1")),
      namespace = configuration.namespace,
      projectedDeletionStamp = 0,
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflixcom",
        notificationType = "email",
        notificationStamp = clock.instant().toEpochMilli()
      )
    )

    resourceRepository.upsert(markedResource)

    resourceRepository.getNumMarkedResources() shouldMatch equalTo(1L)
  }

  @Test
  fun `scanning should work`() {
    for (i in 1..200) {
      resourceRepository.upsert(defaultMarkedResource.copy(resource = TestResource(resourceId = "$i")))
    }

    resourceRepository.getNumMarkedResources() shouldMatch equalTo(200L)
    resourceRepository.getMarkedResources().size shouldMatch equalTo(200)
  }

  @Test
  fun `should store a list of deleted resources`() {
    resourceRepository.upsert(defaultMarkedResource)
    resourceRepository.remove(defaultMarkedResource)

    resourceRepository.getMarkedResources() shouldMatch equalTo(emptyList())
    resourceRepository.getDeleted() shouldMatch equalTo(listOf(DeleteInfo("test", "test", "namespace")))
  }
}
