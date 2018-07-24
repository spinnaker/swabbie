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

package com.netflix.spinnaker.swabbie.events

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.swabbie.ResourceTypeHandlerTest.workConfiguration
import com.netflix.spinnaker.swabbie.ResourceStateRepository
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock

object ResourceStateManagerTest {
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val resourceTagger = mock<ResourceTagger>()
  private val clock = Clock.systemDefaultZone()

  private var resource = TestResource("testResource")
  private var configuration = workConfiguration()

  @AfterEach
  fun cleanup() {
    reset(resourceStateRepository, resourceTagger)
  }

  @Test
  fun `should update state and tag resource when it's marked`() {
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("violates rule 1", "ruleName")),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    val event = MarkResourceEvent(markedResource, configuration)
    val resourceStateManager = ResourceStateManager(
      resourceStateRepository = resourceStateRepository,
      clock = Clock.systemDefaultZone(),
      registry = NoopRegistry(),
      resourceTagger = resourceTagger
    )

    resourceStateManager.handleEvents(event)

    verify(resourceTagger).tag(markedResource, configuration, "${event.markedResource.typeAndName()} scheduled to be cleaned up on ${event.markedResource.humanReadableDeletionTime(clock)}")
    verify(resourceStateRepository).upsert(
      argWhere {
        !it.deleted &&
          it.markedResource == markedResource &&
          it.statuses.size == 1 && it.statuses[0].name == "MARK"
      }
    )
  }

  @Test
  fun `should update state and untag resource when it's unmarked`() {
    val markedResource = MarkedResource(
      resource = resource,
      summaries = emptyList(),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    val event = UnMarkResourceEvent(markedResource, configuration)
    val resourceStateManager = ResourceStateManager(
      resourceStateRepository = resourceStateRepository,
      clock = Clock.systemDefaultZone(),
      registry = NoopRegistry(),
      resourceTagger = resourceTagger
    )

    // previously marked resource
    whenever(resourceStateRepository.get(markedResource.resourceId, configuration.namespace)) doReturn
      ResourceState(
        deleted = false,
        markedResource = markedResource,
        statuses = mutableListOf(
          Status(name = Action.MARK.name, timestamp = clock.instant().minusMillis(3000).toEpochMilli())
        )
      )

    resourceStateManager.handleEvents(event)

    verify(resourceTagger).unTag(markedResource, configuration, "${event.markedResource.typeAndName()}. No longer a cleanup candidate")

    // should have two statuses with UNMARK being the latest
    verify(resourceStateRepository).upsert(
      argWhere {
        !it.deleted &&
          it.markedResource == markedResource &&
          it.statuses.size == 2 && it.statuses[0].name == "MARK" &&
          it.statuses[1].name == "UNMARK" &&
          it.statuses[0].timestamp < it.statuses[1].timestamp
      }
    )
  }

  @Test
  fun `should update state and untag resource when it's deleted`() {
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("violates rule 1", "ruleName")),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    val event = DeleteResourceEvent(markedResource, configuration)
    val resourceStateManager = ResourceStateManager(
      resourceStateRepository = resourceStateRepository,
      clock = Clock.systemDefaultZone(),
      registry = NoopRegistry(),
      resourceTagger = resourceTagger
    )

    // previously marked resource
    whenever(resourceStateRepository.get(markedResource.resourceId, configuration.namespace)) doReturn
      ResourceState(
        deleted = false,
        markedResource = markedResource,
        statuses = mutableListOf(
          Status(name = Action.MARK.name, timestamp = clock.instant().minusMillis(3000).toEpochMilli())
        )
      )

    resourceStateManager.handleEvents(event)

    verify(resourceTagger).unTag(markedResource, configuration, "Removing tag for now deleted ${event.markedResource.typeAndName()}")

    // should have two statuses with DELETE being the latest
    verify(resourceStateRepository).upsert(
      argWhere {
        it.deleted &&
          it.markedResource == markedResource &&
          it.statuses.size == 2 && it.statuses[0].name == "MARK" &&
          it.statuses[1].name == "DELETE" &&
          it.statuses[0].timestamp < it.statuses[1].timestamp
      }
    )
  }
}
