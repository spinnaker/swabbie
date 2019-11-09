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

package com.netflix.spinnaker.handler

import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.AbstractResourceTypeHandler
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AllowListExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.validateMockitoUsage
import com.nhaarman.mockito_kotlin.doReturn
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import strikt.assertions.isTrue
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isNotNull
import strikt.assertions.isNotEmpty
import strikt.assertions.isGreaterThan
import strikt.assertions.isEqualTo
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

abstract class ResourceTypeHandlerTests<T : AbstractResourceTypeHandler<*>> {
  protected val clock: Clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
  protected abstract fun initialize()
  protected abstract fun finalize()

  protected abstract fun rules(): List<Rule<*>>
  protected abstract fun workConfiguration(): WorkConfiguration
  protected abstract fun eventPublisher(): ApplicationEventPublisher
  protected abstract fun dynamicConfigService(): DynamicConfigService
  protected abstract fun taskTrackingRepository(): TaskTrackingRepository
  protected abstract fun resourceStateRepository(): ResourceStateRepository
  protected abstract fun resourceTrackingRepository(): ResourceTrackingRepository

  protected abstract fun getResourceById(resourceId: String): Any
  protected abstract fun ensureThereIsACleanupCandidate(resourceId: String)
  protected abstract fun ensureThereAreCleanupCandidates(resourceIds: List<String>, withViolations: Boolean = false)

  protected abstract fun ensureTasksToBeSubmitted(resourceIds: List<String>): List<String>
  protected abstract fun deleteTaskSubmitted(count: Int): Boolean

  protected abstract fun subject(): T
  protected val exclusionPolicies = listOf(
    LiteralExclusionPolicy(),
    AllowListExclusionPolicy()
  )

  private lateinit var handler: T
  private lateinit var workConfiguration: WorkConfiguration
  private lateinit var namespace: String

  @BeforeEach
  open fun setup() {
    handler = subject()
    initialize()
    workConfiguration = workConfiguration()
    namespace = workConfiguration.namespace

    whenever(dynamicConfigService().getConfig(
      any<Class<*>>(), eq("$namespace.max-items-processed-per-cycle"), any())
    ) doReturn workConfiguration.maxItemsProcessedPerCycle
    validateMockitoUsage()
  }

  @AfterEach
  open fun cleanup() {
    finalize()
    reset(
      eventPublisher(),
      resourceTrackingRepository(),
      dynamicConfigService(),
      taskTrackingRepository(),
      resourceStateRepository()
    )
  }

  @Test
  fun `should have rules to eval resources`() {
    expectThat(rules()).isNotEmpty()
  }

  @Test
  fun `should handle work`() {
    expectThat(
      handler.handles(workConfiguration)
    ).isTrue()
  }

  @Test
  fun `should get resources`() {
    val resourceIds = listOf("resource1")
    expectThat(
      handler.getCandidates(workConfiguration = workConfiguration)!!.size
    ).isEqualTo(0)

    ensureThereAreCleanupCandidates(resourceIds)

    expectThat(
      handler.getCandidates(workConfiguration = workConfiguration)!!.size
    ).isGreaterThan(0)
  }

  @Test
  fun `should get single resource`() {
    expectThat(
      handler.getCandidate(
        resourceId = "resource1",
        workConfiguration = workConfiguration
      )
    ).isNull()

    ensureThereIsACleanupCandidate(resourceId = "resource1")

    expectThat(
      handler.getCandidate(
        resourceId = "resource1",
        workConfiguration = workConfiguration
      )
    ).isNotNull()
  }

  @Test
  fun `should mark violating resources for deletion`() {
    val resourceIds = listOf("resource1", "resource2")
    ensureThereAreCleanupCandidates(resourceIds, withViolations = true)

    handler.mark(workConfiguration = workConfiguration)

    verify(resourceTrackingRepository(), times(resourceIds.size)).upsert(any(), any())
    verify(eventPublisher(), times(resourceIds.size)).publishEvent(any<MarkResourceEvent>())

    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
    verify(eventPublisher(), never()).publishEvent(any<UnMarkResourceEvent>())
  }

  @Test
  fun `should not mark valid resources for deletion`() {
    val resourceIdsWithNoViolations = listOf("resource1", "resource2")
    ensureThereAreCleanupCandidates(resourceIdsWithNoViolations, withViolations = false)

    handler.mark(workConfiguration = workConfiguration)

    verify(resourceTrackingRepository(), never()).upsert(any(), any())
    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
    verify(eventPublisher(), never()).publishEvent(any<UnMarkResourceEvent>())
  }

  @Test
  fun `should not mark resource if exclusion policy applies`() {
    val resourceIds = listOf("resource1", "resource2")
    val workConfiguration = workConfiguration.copy(
      maxAge = 1,
      exclusions = setOf(
        Exclusion()
          .withType(ExclusionType.Literal.toString())
          .withAttributes(
            setOf(
              Attribute()
                .withKey("resourceId")
                .withValue(
                  resourceIds
                )
            )
          )
      )
    )

    ensureThereAreCleanupCandidates(resourceIds, withViolations = true)

    handler.mark(workConfiguration = workConfiguration)

    verify(resourceTrackingRepository(), never()).upsert(any(), any())
    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
    verify(eventPublisher(), never()).publishEvent(any<UnMarkResourceEvent>())
  }

  @Test
  fun `should delete marked resources if owner was notified`() {
    val resourceIds = listOf("resource1", "resource2")
    ensureThereAreCleanupCandidates(resourceIds, withViolations = true)

    whenever(resourceTrackingRepository().getMarkedResourcesToDelete()) doReturn
      resourceIds.map {
        val resource = getResourceById(it) as Resource
        MarkedResource(
          resource = resource,
          summaries = listOf(Summary("unused", "rule")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = clock.millis(),
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "email",
            notificationStamp = resource.createTs
          )
        )
      }

    val taskIds = ensureTasksToBeSubmitted(resourceIds)
    expectThat(taskIds).isNotEmpty()

    handler.delete(workConfiguration = workConfiguration)

    expectThat(deleteTaskSubmitted(count = taskIds.size)).isTrue()
    verify(resourceTrackingRepository(), times(resourceIds.size)).remove(any())

    verify(taskTrackingRepository(), times(taskIds.size)).add(any(), any())
    verify(eventPublisher(), times(taskIds.size)).publishEvent(any<DeleteResourceEvent>())

    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
    verify(eventPublisher(), never()).publishEvent(any<UnMarkResourceEvent>())
  }

  @Test
  fun `should not delete valid marked resources and ensure they are unmarked`() {
    val resourceIds = listOf("resource1", "resource2")
    ensureThereAreCleanupCandidates(resourceIds, withViolations = false)

    whenever(resourceTrackingRepository().getMarkedResourcesToDelete()) doReturn
      resourceIds.map {
        val resource = getResourceById(it) as Resource
        MarkedResource(
          resource = resource,
          summaries = listOf(Summary("unused", "rule")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = clock.millis(),
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "email",
            notificationStamp = resource.createTs
          )
        )
      }

    val taskIds = ensureTasksToBeSubmitted(resourceIds)
    expectThat(taskIds).isNotEmpty()

    handler.delete(workConfiguration = workConfiguration)

    expectThat(deleteTaskSubmitted(count = 0)).isTrue()
    verify(taskTrackingRepository(), never()).add(any(), any())

    verify(eventPublisher(), times(resourceIds.size)).publishEvent(any<UnMarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())
  }

  @Test
  fun `should not delete marked resources if exclusion policy applies`() {
    val resourceIds = listOf("resource1", "resource2")
    val workConfiguration = workConfiguration.copy(
      maxAge = 1,
      exclusions = setOf(
        Exclusion()
          .withType(ExclusionType.Literal.toString())
          .withAttributes(
            setOf(
              Attribute()
                .withKey("resourceId")
                .withValue(
                  resourceIds
                )
            )
          )
      )
    )

    ensureThereAreCleanupCandidates(resourceIds, withViolations = true)

    whenever(resourceTrackingRepository().getMarkedResourcesToDelete()) doReturn
      resourceIds.map {
        val resource = getResourceById(it) as Resource
        MarkedResource(
          resource = resource,
          summaries = listOf(Summary("unused", "rule")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = clock.millis(),
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "email",
            notificationStamp = resource.createTs
          )
        )
      }

    val taskIds = ensureTasksToBeSubmitted(resourceIds)
    expectThat(taskIds).isNotEmpty()

    handler.delete(workConfiguration = workConfiguration)

    expectThat(deleteTaskSubmitted(count = taskIds.size)).isFalse()
    verify(taskTrackingRepository(), never()).add(any(), any())
    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())

    verify(eventPublisher(), times(resourceIds.size)).publishEvent(any<UnMarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
  }

  @Test
  fun `should only delete allowlisted resources`() {
    val resourceIds = listOf("resource1", "resource2")
    val workConfiguration = workConfiguration.copy(
      maxAge = 1,
      exclusions = setOf(
        Exclusion()
          .withType(ExclusionType.Allowlist.toString())
          .withAttributes(
            setOf(
              Attribute()
                .withKey("resourceId")
                .withValue(
                  listOf("resource3")
                )
            )
          )
      )
    )

    ensureThereAreCleanupCandidates(resourceIds, withViolations = true)
    whenever(resourceTrackingRepository().getMarkedResourcesToDelete()) doReturn
      resourceIds.map {
        val resource = getResourceById(it) as Resource
        MarkedResource(
          resource = resource,
          summaries = listOf(Summary("unused", "rule")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = clock.millis(),
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "email",
            notificationStamp = resource.createTs
          )
        )
      }

    val taskIds = ensureTasksToBeSubmitted(resourceIds)
    expectThat(taskIds).isNotEmpty()

    handler.delete(workConfiguration = workConfiguration)

    expectThat(deleteTaskSubmitted(count = 0)).isTrue()

    verify(resourceTrackingRepository(), times(resourceIds.count())).remove(any())
    verify(eventPublisher(), times(resourceIds.size)).publishEvent(any<UnMarkResourceEvent>())

    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
  }

  @Test
  fun `should not delete if not notified and notifications are enabled`() {
    val resourceIds = listOf("resource1", "resource2")
    val workConfiguration = workConfiguration.copy(notificationConfiguration = NotificationConfiguration(enabled = true))

    ensureThereAreCleanupCandidates(resourceIds, withViolations = true)
    whenever(resourceTrackingRepository().getMarkedResourcesToDelete()) doReturn
      resourceIds.map {
        val resource = getResourceById(it) as Resource
        MarkedResource(
          resource = resource,
          summaries = listOf(Summary("unused", "rule")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = clock.millis(),
          notificationInfo = null
        )
      }

    val taskIds = ensureTasksToBeSubmitted(resourceIds)
    expectThat(taskIds).isNotEmpty()

    handler.delete(workConfiguration = workConfiguration)

    expectThat(deleteTaskSubmitted(count = taskIds.count())).isFalse()
    verify(taskTrackingRepository(), never()).add(any(), any())
    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())

    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
    verify(eventPublisher(), never()).publishEvent(any<UnMarkResourceEvent>())
  }

  @Test
  fun `should not delete opted out resources and ensure they are unmarked`() {
    val resourceIds = listOf("resource1", "resource2")
    ensureThereAreCleanupCandidates(resourceIds, withViolations = true)

    val markedResources = resourceIds.map {
      val resource = getResourceById(it) as Resource
      MarkedResource(
        resource = resource,
        summaries = listOf(Summary("unused", "rule")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis(),
        notificationInfo = NotificationInfo(
          recipient = "test@netflix.com",
          notificationType = "email",
          notificationStamp = resource.createTs
        )
      )
    }

    whenever(resourceTrackingRepository().getMarkedResourcesToDelete()) doReturn markedResources

    whenever(resourceStateRepository().getAll()) doReturn
      markedResources.map {
        ResourceState(
          optedOut = true,
          currentStatus = Status(Action.OPTOUT.name, Instant.now().toEpochMilli()),
          statuses = mutableListOf(
            Status(Action.OPTOUT.name, Instant.now().toEpochMilli())
          ),
          markedResource = it
        )
      }

    val taskIds = ensureTasksToBeSubmitted(resourceIds)
    expectThat(taskIds).isNotEmpty()

    handler.delete(workConfiguration = workConfiguration)

    expectThat(deleteTaskSubmitted(count = taskIds.count())).isFalse()
    verify(taskTrackingRepository(), never()).add(any(), any())

    verify(eventPublisher(), times(resourceIds.size)).publishEvent(any<UnMarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<MarkResourceEvent>())
    verify(eventPublisher(), never()).publishEvent(any<OwnerNotifiedEvent>())
    verify(eventPublisher(), never()).publishEvent(any<DeleteResourceEvent>())
  }
}
