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

package com.netflix.spinnaker.swabbie.notifications

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.StatusChangeEvent
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.events.OwnerNotifiedEvent
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.test.InMemoryNotificationQueue
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.whenever
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.times

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import java.time.Clock

object NotificationSenderTest {
  private val notifier = mock<Notifier>()
  private val clock = Clock.systemDefaultZone()
  private var notificationQueue = InMemoryNotificationQueue()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()

  private val workConfiguration1 = WorkConfigurationTestHelper
    .generateWorkConfiguration(namespace = "ns1", resourceType = "type1")
  private val workConfiguration2 = WorkConfigurationTestHelper
    .generateWorkConfiguration(namespace = "ns2", resourceType = "type2")

  private val notificationService = NotificationSender(
    lockingService = LockingService.NOOP(),
    notifier = notifier,
    clock = clock,
    applicationEventPublisher = applicationEventPublisher,
    resourceTrackingRepository = resourceRepository,
    notificationQueue = notificationQueue,
    registry = NoopRegistry(),
    workConfigurations = listOf(workConfiguration1, workConfiguration2)
  )

  private val now = clock.millis()

  @BeforeEach
  fun setup() {
    notificationService
      .onDiscoveryUpCallback(
        RemoteStatusChangedEvent(StatusChangeEvent(InstanceInfo.InstanceStatus.DOWN, InstanceInfo.InstanceStatus.UP)))
  }

  @AfterEach
  fun cleanup() {
    reset(
      notifier,
      resourceRepository,
      applicationEventPublisher
    )
  }

  @Test
  fun `should not notify if there is nothing in the notification queue`() {
    whenever(
      resourceRepository.getMarkedResources()
    ) doReturn listOf(
      createMarkedResource(workConfiguration = workConfiguration2, id = "3", owner = "test@netflix.com")
    )

    verify(notifier, never()).notify(any())
    verify(applicationEventPublisher, never()).publishEvent(any<OwnerNotifiedEvent>())
  }

  @Test
  fun `should notify`() {
    val owner1 = "test@netflix.com"
    val resource1 = createMarkedResource(workConfiguration = workConfiguration1, id = "1", owner = owner1)
    val resource2 = createMarkedResource(workConfiguration = workConfiguration1, id = "2", owner = owner1)
    val resource3 = createMarkedResource(workConfiguration = workConfiguration2, id = "3", owner = owner1)

    val resources = listOf(resource1, resource2, resource3)

    notificationQueue.add(
      NotificationSender.NotificationTask(
        resourceType = workConfiguration1.resourceType,
        namespace = workConfiguration1.namespace
      ))

    whenever(
      resourceRepository.getMarkedResources()
    ) doReturn resources

    whenever(
      notifier.notify(any())
    ) doReturn Notifier.NotificationResult(owner1, Notifier.NotificationType.EMAIL, success = true)

    notificationService.sendNotifications()

    argumentCaptor<Notifier.Envelope>().apply {
      verify(notifier, times(1)).notify(capture())
      val passedResources = firstValue.resources.map { it.first }
      expectThat(passedResources)
        .isNotEmpty()
        .isEqualTo(
          listOf(resource1, resource2)
        )
    }

    // Should publish events for resource1 and resource2 since they match the resource type
    verify(applicationEventPublisher, times(2)).publishEvent(any<OwnerNotifiedEvent>())

    // Resources that have been notified on will have notification information including a non-null notification timestamp
    listOf(resource1, resource2).forEach {
      expectThat(it.notificationInfo)
        .isNotNull()
        .get { notificationStamp }
        .isNotNull()

      // resource 3 was not notified on because its resource type is not included in the notification task queue
      expectThat(resource3.notificationInfo)
        .isNull()
    }
  }

  private fun createMarkedResource(workConfiguration: WorkConfiguration, id: String, owner: String): MarkedResource {
    return MarkedResource(
      resource = TestResource(resourceId = id, name = id, resourceType = workConfiguration.resourceType),
      summaries = listOf(Summary("invalid", "rule $id")),
      namespace = workConfiguration.namespace,
      projectedDeletionStamp = now,
      markTs = now,
      resourceOwner = owner
    )
  }
}
