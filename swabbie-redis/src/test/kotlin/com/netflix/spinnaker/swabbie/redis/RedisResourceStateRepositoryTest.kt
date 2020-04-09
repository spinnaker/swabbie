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
import com.netflix.spinnaker.config.resourceDeserializerModule
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.kork.test.time.MutableClock
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.test.TestResource
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import redis.clients.jedis.JedisPool
import strikt.api.expect
import strikt.assertions.hasSize
import strikt.assertions.isNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
object RedisResourceStateRepositoryTest {
  private val embeddedRedis = EmbeddedRedis.embed()
  private val jedisPool = embeddedRedis.pool as JedisPool
  private val objectMapper = ObjectMapper().apply {
    registerSubtypes(TestResource::class.java)
    registerModule(KotlinModule())
    registerModule(resourceDeserializerModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  }

  private val clock = MutableClock()
  private const val deletedRetentionDays = 3L
  private var maxDeleted = 5

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
  private val markStatus = Status("MARK", clock.instant().toEpochMilli().minus(TimeUnit.DAYS.toMillis(3)))
  private val notifyStatus = Status("NOTIFY", clock.instant().toEpochMilli().minus(TimeUnit.DAYS.toMillis(2)))
  private val deleteStatus = Status("DELETE", clock.instant().toEpochMilli().minus(TimeUnit.DAYS.toMillis(1)))

  private val markedState = ResourceState(
    markedResource = defaultMarkedResource,
    deleted = false,
    optedOut = false,
    statuses = mutableListOf(markStatus),
    currentStatus = markStatus
  )

  val notifyState = markedState.copy(
    statuses = mutableListOf(markStatus, notifyStatus),
    currentStatus = notifyStatus
  )

  val deletedState = markedState.copy(
    statuses = mutableListOf(markStatus, notifyStatus, deleteStatus),
    currentStatus = deleteStatus
  )

  private val resourceStateRepository = RedisResourceStateRepository(
    RedisClientSelector(listOf(JedisClientDelegate("primaryDefault", jedisPool))),
    objectMapper,
    clock,
    NoopRegistry(),
    deletedRetentionDays,
    maxDeleted
  )

  @BeforeEach
  fun setup() {
    jedisPool.resource.flushAll()
  }

  @AfterAll
  fun cleanup() {
    embeddedRedis.destroy()
  }

  @Test
  fun `adds resource state correctly`() {
    resourceStateRepository.upsert(markedState)
    expect {
      that(resourceStateRepository.getAll()).hasSize(1)
      that(resourceStateRepository.get(markedState.markedResource.resourceId, markedState.markedResource.namespace)).isNotNull()
    }
  }

  @Test
  fun `removes resource state correctly`() {
    resourceStateRepository.upsert(markedState)
    resourceStateRepository.remove(markedState.markedResource.resourceId, markedState.markedResource.namespace)

    expect {
      that(resourceStateRepository.getAll()).hasSize(0)
    }
  }

  @Test
  fun `updates state correctly with notify`() {
    resourceStateRepository.upsert(notifyState)

    expect {
      that(resourceStateRepository.getByStatus("MARK")).hasSize(0)
      that(resourceStateRepository.getByStatus("NOTIFY")).hasSize(1)
    }
  }

  @Test
  fun `updates state correctly with delete`() {
    resourceStateRepository.upsert(deletedState)

    expect {
      that(resourceStateRepository.getByStatus("MARK")).hasSize(0)
      that(resourceStateRepository.getByStatus("DELETE")).hasSize(1)
    }
  }

  @Test
  fun `removes resource after it has been deleted and time has passed`() {
    resourceStateRepository.upsert(deletedState)
    resourceStateRepository.cleanDeletedResources()

    expect {
      that(resourceStateRepository.getAll()).hasSize(1)
    }

    clock.incrementBy(Duration.ofDays(1L))

    resourceStateRepository.cleanDeletedResources()

    expect {
      that(resourceStateRepository.getAll()).hasSize(0)
    }
  }
}
