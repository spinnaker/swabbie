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

import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

object EchoNotifierTest {
  private val echoService = mock<EchoService>()
  private val subject = EchoNotifier(echoService)

  @Test
  fun `should notify`() {
    val now = System.currentTimeMillis()
    val workConfiguration = WorkConfigurationTestHelper.generateWorkConfiguration(
      notificationConfiguration = NotificationConfiguration(types = listOf(Notifier.NotificationType.EMAIL.name))
    )

    val markedResource = MarkedResource(
      resource = TestResource(resourceId = "1"),
      summaries = listOf(Summary("invalid resource", "rule x")),
      namespace = workConfiguration.namespace,
      markTs = now,
      projectedDeletionStamp = now,
      resourceOwner = "test@netflix.com"
    )

    val result = subject.notify(
      recipient = markedResource.resourceOwner,
      notificationContext = mapOf(),
      notificationConfiguration = workConfiguration.notificationConfiguration
    )

    verify(echoService).create(any())
    expectThat(result.success).isEqualTo(true)
  }
}
