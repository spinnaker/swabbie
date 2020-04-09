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
import com.netflix.spinnaker.config.resourceDeserializerModule
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.test.TestResource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.util.Assert
import redis.clients.jedis.JedisPool

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
object RedisTaskTrackingRepositoryTest {
  private val embeddedRedis = EmbeddedRedis.embed()
  private val jedisPool = embeddedRedis.pool as JedisPool
  private val objectMapper = ObjectMapper().apply {
    registerSubtypes(TestResource::class.java)
    registerModule(KotlinModule())
    registerModule(resourceDeserializerModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  }
  private val clock = Clock.fixed(Instant.parse("2018-05-24T12:34:56Z"), ZoneOffset.UTC)
  private var threeDaysAgo = clock.instant().minusMillis(259200000).toEpochMilli()

  private val trackingRepository = RedisTaskTrackingRepository(
    RedisClientSelector(listOf(JedisClientDelegate("primaryDefault", jedisPool))), objectMapper, clock
  )

  private val configuration = WorkConfiguration(
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

  private val markedResourceWithViolations = MarkedResource(
    resource = TestResource("marked resource due for deletion now"),
    summaries = listOf(Summary("invalid resource 1", "rule 1")),
    namespace = configuration.namespace,
    projectedDeletionStamp = 0,
    notificationInfo = NotificationInfo(
      recipient = "yolo@netflixcom",
      notificationType = "email",
      notificationStamp = threeDaysAgo
    )
  )

  private val taskCompleteDeleteInfo = TaskCompleteEventInfo(
    action = Action.DELETE,
    markedResources = listOf(markedResourceWithViolations),
    workConfiguration = configuration,
    submittedTimeMillis = null
  )

  private val oldTaskInfo = TaskCompleteEventInfo(
    action = Action.DELETE,
    markedResources = listOf(markedResourceWithViolations),
    workConfiguration = configuration,
    submittedTimeMillis = threeDaysAgo
  )

  private val taskId = "01CRXYGDMCBPS95GRZES56Y44P"

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
  fun `add and remove should work`() {
    trackingRepository.add(taskId, taskCompleteDeleteInfo)

    Assert.notEmpty(trackingRepository.getInProgress(), "should have inserted task")
  }

  @Test
  fun `nothing should be present on startup`() {
    Assert.isTrue(trackingRepository.getInProgress() == emptySet<String>(), "should have no resources to start")
  }

  @Test
  fun `set succeeded should produce correct behavior`() {
    trackingRepository.add(taskId, taskCompleteDeleteInfo)

    Assert.notEmpty(trackingRepository.getInProgress(), "should have inserted task")

    trackingRepository.setSucceeded(taskId)

    Assert.notEmpty(trackingRepository.getSucceeded(), "succeeded task should be tracked")
    Assert.isTrue(trackingRepository.getFailed() == emptySet<String>(), "should be no failed tasks")
    Assert.isTrue(trackingRepository.getInProgress() == emptySet<String>(), "should be no in progress tasks")
  }

  @Test
  fun `should delete old task when asked`() {
    trackingRepository.add(taskId, oldTaskInfo)

    trackingRepository.cleanUpFinishedTasks(2)

    Assert.isTrue(trackingRepository.getInProgress() == emptySet<String>(), "should have no more resources")
  }

  @Test
  fun `should return multiple resources when applicable`() {
    val deleteTaskId = "deleteTaskId"
    val oldTaskId = "oldTaskId"
    val otherTaskId = "otherTaskId"
    trackingRepository.add(deleteTaskId, taskCompleteDeleteInfo)
    trackingRepository.add(oldTaskId, oldTaskInfo)
    trackingRepository.add(otherTaskId, taskCompleteDeleteInfo)

    trackingRepository.setSucceeded(oldTaskId)
    trackingRepository.setFailed(deleteTaskId)
    trackingRepository.setSucceeded(otherTaskId)

    Assert.isTrue(trackingRepository.getInProgress().isEmpty(), "no more in progress")
    Assert.isTrue(trackingRepository.getSucceeded().size == 2, "has two tasks")
    Assert.isTrue(trackingRepository.getFailed().size == 1, "has one task")
  }
}
