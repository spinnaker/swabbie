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

package com.netflix.spinnaker.swabbie.events

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.nhaarman.mockito_kotlin.argWhere
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import java.time.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

object ResourceTrackingManagerTest {
  private val resourceTrackingRepository = mock<ResourceTrackingRepository>()
  private val clock = Clock.systemDefaultZone()
  private val registry = NoopRegistry()

  private var resource = TestResource("testResource")
  private var configuration = WorkConfigurationTestHelper.generateWorkConfiguration()

  private var subject = ResourceTrackingManager(
    resourceTrackingRepository = resourceTrackingRepository
  )

  @AfterEach
  fun cleanup() {
    reset(resourceTrackingRepository)
  }

  @Test
  fun `should update state on delete event`() {
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("violates rule 1", "ruleName")),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis()
    )
    val event = DeleteResourceEvent(markedResource, configuration)

    subject.handleEvents(event)

    verify(resourceTrackingRepository).remove(
      argWhere { it == markedResource }
    )
  }
}
