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

package com.netflix.spinnaker.swabbie

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.model.Grouping
import com.netflix.spinnaker.swabbie.model.GroupingType
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationResult
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationType.EMAIL
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.rules.RulesEngine
import com.netflix.spinnaker.swabbie.test.InMemoryNotificationQueue
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_PROVIDER_TYPE
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_TYPE
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.argWhere
import com.nhaarman.mockito_kotlin.check
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.inOrder
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue

object ResourceTypeHandlerTest {
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val clock = Clock.fixed(Instant.parse("2018-11-26T09:30:00Z"), ZoneOffset.UTC)
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val ownerResolver = mock<ResourceOwnerResolver<TestResource>>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val dynamicConfigService = mock<DynamicConfigService>()
  private val notifier = mock<Notifier>()
  private var notificationQueue = InMemoryNotificationQueue()
  private val rulesEngine = mock<RulesEngine>()
  private val summaries = listOf(Summary(description = "test rule description", ruleName = "test rule name"))
  private val createTimestampTenDaysAgo = clock.instant().minus(10, ChronoUnit.DAYS).toEpochMilli()
  private val defaultResource = createTestResource(
    resourceId = "testResource",
    createTs = createTimestampTenDaysAgo
  )

  private val workConfiguration = WorkConfigurationTestHelper
    .generateWorkConfiguration(resourceType = TEST_RESOURCE_TYPE, cloudProvider = TEST_RESOURCE_PROVIDER_TYPE)

  // must update rules and candidates before using
  private val defaultHandler = TestResourceTypeHandler(
    clock = clock,
    rulesEngine = rulesEngine,
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    exclusionPolicies = mutableListOf(mock()),
    ownerResolver = ownerResolver,
    applicationEventPublisher = applicationEventPublisher,
    simulatedCandidates = mutableListOf(),
    notifier = notifier,
    taskTrackingRepository = taskTrackingRepository,
    resourceUseTrackingRepository = resourceUseTrackingRepository,
    dynamicConfigService = dynamicConfigService,
    notificationQueue = notificationQueue
  )

  @BeforeEach
  fun setup() {
    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle
    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.deleteSpreadMs))) doReturn
      3600000
  }

  @AfterEach
  fun cleanup() {
    reset(
      resourceRepository,
      resourceStateRepository,
      applicationEventPublisher,
      ownerResolver,
      taskTrackingRepository,
      notifier,
      rulesEngine
    )
    defaultHandler.clearCandidates()
    notificationQueue.popAll()
  }

  @Test
  fun `should track a violating resource`() {
    whenever(rulesEngine.evaluate<TestResource>(any(), any())) doReturn
      listOf(Summary(description = "test rule description", ruleName = "test rule name"))

    defaultHandler.setCandidates(mutableListOf(defaultResource))
    defaultHandler.mark(workConfiguration)

    inOrder(resourceRepository, applicationEventPublisher) {
      verify(resourceRepository).upsert(any(), any())
      verify(applicationEventPublisher).publishEvent(any<MarkResourceEvent>())
    }
  }

  @Test
  fun `should track violating resources`() {
    val resources: MutableList<TestResource> = mutableListOf(
      createTestResource(resourceId = "1", createTs = createTimestampTenDaysAgo),
      createTestResource(resourceId = "2", createTs = createTimestampTenDaysAgo),
      createTestResource(resourceId = "3", createTs = createTimestampTenDaysAgo)
    )

    whenever(rulesEngine.evaluate<TestResource>(argWhere { it.resourceId in listOf("1", "2") }, any())) doReturn
      listOf(Summary(description = "test rule description", ruleName = "test rule name"))

    defaultHandler.setCandidates(resources)

    defaultHandler.mark(workConfiguration)

    verify(resourceRepository, times(2)).upsert(any(), any())
    verify(applicationEventPublisher, times(2)).publishEvent(any<MarkResourceEvent>())
  }

  @Test
  fun `should delete a resource`() {
    val candidates = mutableListOf(
      createTestResource(resourceId = "1", createTs = createTimestampTenDaysAgo),
      createTestResource(resourceId = "2", createTs = createTimestampTenDaysAgo)
    )

    defaultHandler.setCandidates(candidates)

    val fifteenDaysAgo = clock.instant().minus(15, ChronoUnit.DAYS).toEpochMilli()
    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn
      listOf(
        MarkedResource(
          resource = TestResource("1"),
          summaries = listOf(Summary("invalid resource 1", "rule 1")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "yolo@netflix.com",
            notificationType = "email",
            notificationStamp = clock.millis()
          )
        ),
        MarkedResource(
          resource = TestResource("2"),
          summaries = listOf(Summary("invalid resource 2", "rule 2")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "yolo@netflix.com",
            notificationType = "email",
            notificationStamp = clock.millis()
          )
        )
      )

    whenever(rulesEngine.evaluate<TestResource>(any(), any())) doReturn
      listOf(Summary(description = "test rule description", ruleName = "test rule name"))

    defaultHandler.delete(workConfiguration)

    verify(taskTrackingRepository, times(2)).add(any(), any())
    candidates.size shouldMatch equalTo(0)
  }

  @Test
  fun `should ignore opted out resources during delete`() {
    val resource = TestResource(resourceId = "testResource", name = "testResourceName")
    val fifteenDaysAgo = clock.instant().minus(15, ChronoUnit.DAYS).toEpochMilli()
    val markedResource = MarkedResource(
      resource = resource,
      summaries = summaries,
      namespace = workConfiguration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = fifteenDaysAgo,
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflix.com",
        notificationType = "email",
        notificationStamp = clock.millis()
      )
    )

    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn
      listOf(markedResource)

    whenever(rulesEngine.evaluate<TestResource>(any(), any())) doReturn summaries

    whenever(resourceStateRepository.getAll()) doReturn
      listOf(
        ResourceState(
          optedOut = true,
          currentStatus = Status(Action.OPTOUT.name, Instant.now().toEpochMilli()),
          statuses = mutableListOf(
            Status(Action.MARK.name, Instant.now().toEpochMilli()),
            Status(Action.OPTOUT.name, Instant.now().toEpochMilli())
          ),
          markedResource = markedResource
        )
      )

    defaultHandler.delete(workConfiguration)

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should ignore opted out resources during mark`() {
    val resource = TestResource(resourceId = "testResource", name = "testResourceName")
    val fifteenDaysAgo = clock.instant().minus(15, ChronoUnit.DAYS).toEpochMilli()

    val markedResource = MarkedResource(
      resource = resource,
      summaries = summaries,
      namespace = workConfiguration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = fifteenDaysAgo,
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflix.com",
        notificationType = "email",
        notificationStamp = clock.millis()
      )
    )

    whenever(rulesEngine.evaluate<TestResource>(any(), any())) doReturn summaries
    whenever(resourceStateRepository.getAll()) doReturn
      listOf(
        ResourceState(
          optedOut = true,
          currentStatus = Status(Action.OPTOUT.name, Instant.now().toEpochMilli()),
          statuses = mutableListOf(
            Status(Action.MARK.name, Instant.now().toEpochMilli()),
            Status(Action.OPTOUT.name, Instant.now().toEpochMilli())
          ),
          markedResource = markedResource
        )
      )

    defaultHandler.mark(workConfiguration)

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should partition the list of resources to delete by grouping`() {
    // resources 1 & 2 would be grouped into a partition because their names match to the same app
    // resource 3 would be in its own partition
    val resource1 = TestResource("1", name = "testResource-v001", grouping = Grouping("testResource", GroupingType.APPLICATION))
    val resource2 = TestResource("2", name = "testResource-v002", grouping = Grouping("testResource", GroupingType.APPLICATION))
    val resource3 = TestResource("3", name = "random")
    val resource4 = TestResource(
      resourceId = "4",
      name = "my-package-0.0.2",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME)
    )
    val resource5 = TestResource(
      resourceId = "5",
      name = "my-package-0.0.4",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME)
    )

    val workConfiguration = workConfiguration.copy(
      itemsProcessedBatchSize = 2
    )

    val markedResources = listOf(
      MarkedResource(
        resource = resource1,
        summaries = listOf(Summary("invalid resource 1", "rule 1")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource2,
        summaries = listOf(Summary("invalid resource 2", "rule 2")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource3,
        summaries = listOf(Summary("invalid resource random", "rule 3")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource4,
        summaries = listOf(Summary("invalid resource random", "rule 4")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource5,
        summaries = listOf(Summary("invalid resource random", "rule 5")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      )
    )

    defaultHandler.setCandidates(mutableListOf(resource1, resource2, resource3))

    val result = defaultHandler.partitionList(markedResources, workConfiguration)
    Assertions.assertTrue(result.size == 3)
    Assertions.assertTrue(result[0].markedResources.size == 2, "resources 1 & 2 because their names match to the same app")
    Assertions.assertTrue(result[1].markedResources.size == 2)
    Assertions.assertTrue(result[2].markedResources.size == 1)
    Assertions.assertTrue(result[0].markedResources.none { it.name == resource3.name })
    Assertions.assertTrue(result[1].markedResources.all { it.name!!.startsWith("my-package") })
    Assertions.assertTrue(result[2].markedResources.all { it.name == resource3.name })
    Assertions.assertTrue(result[0].offsetMs == 0L)
    Assertions.assertTrue(result[1].offsetMs == 2400000L)
    Assertions.assertTrue(result[2].offsetMs == 1200000L)
  }

  @Test
  fun `partition same owner different grouping should be 2 lists`() {
    val resource1 = TestResource(
      resourceId = "4",
      name = "my-other-package-0.0.2",
      resourceType = "image",
      grouping = Grouping("my-other-package", GroupingType.PACKAGE_NAME)
    )
    val resource2 = TestResource(
      resourceId = "5",
      name = "my-package-0.0.4",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME)
    )

    val workConfiguration = workConfiguration.copy(
      itemsProcessedBatchSize = 2,
      maxItemsProcessedPerCycle = 3
    )

    val markedResources = listOf(
      MarkedResource(
        resource = resource1,
        summaries = listOf(Summary("invalid resource 1", "rule 1")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource2,
        summaries = listOf(Summary("invalid resource 2", "rule 2")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      )
    )

    defaultHandler.setCandidates(mutableListOf(resource1, resource2))

    val result = defaultHandler.partitionList(markedResources, workConfiguration)
    Assertions.assertTrue(result.size == 2)
    Assertions.assertTrue(result[0].markedResources.size == 1)
    Assertions.assertTrue(result[1].markedResources.size == 1)
    Assertions.assertTrue(result[0].markedResources.none { it.name == resource2.name })
    Assertions.assertTrue(result[1].markedResources.none { it.name == resource1.name })
  }

  @Test
  fun `should forget resource if no longer violate a rule`() {
    val markedResource = MarkedResource(
      resource = defaultResource,
      summaries = summaries,
      namespace = workConfiguration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    whenever(resourceRepository.getMarkedResources())
      .thenReturn(listOf(markedResource))
      .thenReturn(emptyList())

    defaultHandler.setCandidates(mutableListOf(defaultResource))
    whenever(rulesEngine.evaluate<TestResource>(any(), any())) doReturn emptyList<Summary>()

    defaultHandler.mark(workConfiguration)

    verify(applicationEventPublisher, times(1)).publishEvent(
      check<UnMarkResourceEvent> { event ->
        expectThat(event.markedResource.resourceId).isEqualTo(markedResource.resourceId)
        expectThat(event.workConfiguration.namespace).isEqualTo(workConfiguration.namespace)
      }
    )
    verify(resourceRepository, times(1)).remove(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should forget resource if it is excluded after being marked`() {
    val markedResource = MarkedResource(
      resource = defaultResource,
      summaries = summaries,
      namespace = workConfiguration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    whenever(resourceRepository.getMarkedResources())
      .thenReturn(listOf(markedResource))

    defaultHandler.setCandidates(mutableListOf(defaultResource))

    defaultHandler.mark(workConfiguration)

    verify(applicationEventPublisher, times(1)).publishEvent(
      check<UnMarkResourceEvent> { event ->
        expectThat(event.markedResource.resourceId).isEqualTo(markedResource.resourceId)
        expectThat(event.workConfiguration.namespace).isEqualTo(workConfiguration.namespace)
      }
    )
    verify(resourceRepository, times(1)).remove(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `delete time should equal now plus retention days`() {
    val workConfiguration = workConfiguration.copy(retention = 2)
    val plus2Days = Instant.parse("2018-11-28T09:30:00Z").toEpochMilli()
    defaultHandler.deletionTimestamp(workConfiguration) shouldMatch equalTo(plus2Days)
  }

  @Test
  fun `update delete timestamp should work`() {
    val timestamp10days = clock.millis().plus(TimeUnit.DAYS.toMillis(10))
    val timestamp1hour = clock.millis().plus(TimeUnit.HOURS.toMillis(1))
    val resource1 = TestResource(
      "1",
      name = "testResource-v001",
      grouping = Grouping("testResource", GroupingType.APPLICATION),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(11))
    )
    val resource2 = TestResource(
      "2",
      name = "testResource-v002",
      grouping = Grouping("testResource", GroupingType.APPLICATION),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10))
    )
    val resource3 = TestResource("3", name = "random", createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10)))
    val resource4 = TestResource(
      resourceId = "4",
      name = "my-package-0.0.2",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10))
    )
    val resource5 = TestResource(
      resourceId = "5",
      name = "my-package-0.0.4",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10))
    )

    val workConfiguration = workConfiguration.copy(
      itemsProcessedBatchSize = 2,
      maxItemsProcessedPerCycle = 3
    )

    val markedResource1 = MarkedResource(
      resource = resource1,
      summaries = listOf(Summary("invalid resource 1", "rule 1")),
      namespace = workConfiguration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = timestamp10days
    )

    val updatedMarkedResource1 = MarkedResource(
      resource = resource1,
      summaries = listOf(Summary("invalid resource 1", "rule 1")),
      namespace = workConfiguration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = timestamp1hour
    )

    val markedResources = listOf(
      markedResource1,
      MarkedResource(
        resource = resource2,
        summaries = listOf(Summary("invalid resource 2", "rule 2")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      ),
      MarkedResource(
        resource = resource3,
        summaries = listOf(Summary("invalid resource random", "rule 3")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      ),
      MarkedResource(
        resource = resource4,
        summaries = listOf(Summary("invalid resource random", "rule 4")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      ),
      MarkedResource(
        resource = resource5,
        summaries = listOf(Summary("invalid resource random", "rule 5")),
        namespace = workConfiguration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      )
    )

    whenever(resourceRepository.getMarkedResources()) doReturn markedResources

    defaultHandler.setCandidates(mutableListOf(defaultResource))

    defaultHandler.recalculateDeletionTimestamp(workConfiguration.namespace, 3600, 1)
    verify(resourceRepository, times(1)).upsert(updatedMarkedResource1)
  }

  @Test
  fun `should schedule notification`() {
    val now = clock.millis()
    val workConfiguration = workConfiguration.copy(notificationConfiguration = NotificationConfiguration(enabled = true))
    expectThat(notificationQueue.isEmpty()).isTrue()

    val markedResource = MarkedResource(
      resource = defaultResource,
      summaries = listOf(Summary("invalid resource random", "rule 5")),
      namespace = workConfiguration.namespace,
      projectedDeletionStamp = now.plus(TimeUnit.DAYS.toMillis(3)),
      markTs = now
    )

    whenever(
      resourceRepository.getMarkedResources()
    ) doReturn listOf(markedResource)

    whenever(
      notifier.notify(any(), any(), any())
    ) doReturn NotificationResult(markedResource.resourceOwner, EMAIL, success = true)

    defaultHandler.setCandidates(mutableListOf(defaultResource))

    defaultHandler.notify(workConfiguration)
    expectThat(notificationQueue.isEmpty()).isFalse()
  }

  @Test
  fun `should skip scheduling notifications`() {
    val now = clock.millis()
    val workConfiguration = workConfiguration.copy(
      notificationConfiguration = NotificationConfiguration(enabled = false)
    )

    val markedResource = MarkedResource(
      resource = defaultResource,
      summaries = listOf(Summary("invalid resource random", "rule 5")),
      namespace = workConfiguration.namespace,
      projectedDeletionStamp = now.plus(TimeUnit.DAYS.toMillis(3)),
      markTs = now
    )

    whenever(
      resourceRepository.getMarkedResources()
    ) doReturn listOf(markedResource)

    defaultHandler.setCandidates(mutableListOf(defaultResource))
    defaultHandler.notify(workConfiguration)

    with(markedResource.notificationInfo) {
      expectThat(this)
        .isNotNull()
        .get { notificationType }
        .isEqualTo(Notifier.NotificationType.NONE.name)

      expectThat(this!!.notificationStamp).isNull()
    }
  }

  private fun createTestResource(resourceId: String, createTs: Long): TestResource {
    return TestResource(resourceId = resourceId, createTs = createTs)
  }
}
