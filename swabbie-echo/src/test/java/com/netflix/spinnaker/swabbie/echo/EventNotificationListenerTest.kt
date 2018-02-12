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

package com.netflix.spinnaker.swabbie.echo

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.swabbie.configuration.ScopeOfWorkConfiguration
import com.netflix.spinnaker.swabbie.events.NotifyOwnerEvent
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.persistence.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

object EventNotificationListenerTest {
  private val echoService = mock<EchoService>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val registry = NoopRegistry()
  private val clock = Clock.systemDefaultZone()
  private val listener = EventNotificationListener(echoService, resourceRepository, registry, clock)

  private val resource = TestResource("testResource")
  private val scopeOfWorkConfiguration = ScopeOfWorkConfiguration(
    namespace = "${resource.cloudProvider}:test:us-east-1:${resource.resourceType}",
    account = Account(name = "test", accountId = "accountId"),
    location = "us-east-1",
    cloudProvider = "aws",
    resourceType = "securityGroup",
    retentionDays = 14,
    exclusions = emptyList()
  )

  @AfterEach
  fun cleanup() {
    reset(echoService, resourceRepository)
  }

  @Test
  fun `should notify`() {
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("violates rule 1", "ruleName")),
      namespace = scopeOfWorkConfiguration.namespace,
      projectedDeletionStamp = System.currentTimeMillis(),
      adjustedDeletionStamp = null,
      createdTs = Instant.now(clock).toEpochMilli()
    )

    listener.onNotifyOwnerEvent(
      NotifyOwnerEvent(markedResource, scopeOfWorkConfiguration)
    )

    verify(echoService).create(any())
    verify(resourceRepository).upsert(any(), any())
  }

  @Test
  fun `should not notify`() {
    val notificationInfo = NotificationInfo(recipient = "yolo@netflix.com", notificationStamp = clock.instant().toEpochMilli())
    listener.onNotifyOwnerEvent(
      NotifyOwnerEvent(
        MarkedResource(
          resource = resource,
          summaries = listOf(Summary("violates rule 1", "ruleName")),
          namespace = "${resource.cloudProvider}:test:us-east-1:${resource.resourceType}",
          projectedDeletionStamp = System.currentTimeMillis(),
          adjustedDeletionStamp = System.currentTimeMillis(),
          notificationInfo = notificationInfo
        ),
        scopeOfWorkConfiguration
      )
    )

    verify(echoService, never()).create(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }
}
