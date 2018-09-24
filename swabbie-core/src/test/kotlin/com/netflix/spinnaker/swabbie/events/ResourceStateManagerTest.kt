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
import com.netflix.spinnaker.swabbie.repositories.ResourceStateRepository
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

object ResourceStateManagerTest {
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val resourceTagger = mock<ResourceTagger>()
  private val clock = Clock.fixed(Instant.parse("2018-05-24T12:34:56Z"), ZoneOffset.UTC)
  private val registry = NoopRegistry()

  private var resource = TestResource("testResource")
  private var configuration = workConfiguration()

  private val markedResourceWithViolations =  MarkedResource(
    resource = resource,
    summaries = listOf(Summary("violates rule 1", "ruleName")),
    namespace = configuration.namespace,
    projectedDeletionStamp = clock.millis(),
    projectedSoftDeletionStamp = clock.millis()
  )

  private val markedResourceNoViolations =  MarkedResource(
    resource = resource,
    summaries = emptyList(),
    namespace = configuration.namespace,
    projectedDeletionStamp = clock.millis(),
    projectedSoftDeletionStamp = clock.millis()
  )

  private val subject = ResourceStateManager(
    resourceStateRepository = resourceStateRepository,
    clock = clock,
    registry = registry,
    resourceTagger = resourceTagger
  )

  @AfterEach
  fun cleanup() {
    reset(resourceStateRepository, resourceTagger)
  }

  @Test
  fun `should update state and tag resource when it's marked`() {
    val event = MarkResourceEvent(markedResourceWithViolations, configuration)

    subject.handleEvents(event)

    verify(resourceTagger).tag(
      markedResource = markedResourceWithViolations,
      workConfiguration = configuration,
      description = "${event.markedResource.typeAndName()} scheduled to be cleaned up on " +
        "${event.markedResource.humanReadableDeletionTime(clock)}")

    verify(resourceStateRepository).upsert(
      argWhere {
        it.markedResource == markedResourceWithViolations && it.currentStatus!!.name == Action.MARK.name
      }
    )
  }

  @Test
  fun `should update state and untag resource when it's unmarked`() {
    val event = UnMarkResourceEvent(markedResourceNoViolations, configuration)

    // previously marked resource
    whenever(resourceStateRepository.get(markedResourceNoViolations.resourceId, configuration.namespace)) doReturn
      ResourceState(
        markedResource = markedResourceNoViolations,
        statuses = mutableListOf(
          Status(name = Action.MARK.name, timestamp = clock.instant().minusMillis(3000).toEpochMilli())
        )
      )

    subject.handleEvents(event)

    verify(resourceTagger)
      .unTag(
        markedResource = markedResourceNoViolations,
        workConfiguration = configuration,
        description = "${event.markedResource.typeAndName()}. No longer a cleanup candidate"
      )

    // should have two statuses with UNMARK being the latest
    verify(resourceStateRepository).upsert(
      argWhere {
        it.markedResource == markedResourceNoViolations && it.statuses.size == 2 && it.statuses[0].name == Action.MARK.name &&
          it.currentStatus!!.name == Action.UNMARK.name && it.currentStatus!!.timestamp > it.statuses.first().timestamp
      }
    )
  }

  @Test
  fun `should update state and untag resource when it's deleted`() {
    val event = DeleteResourceEvent(markedResourceWithViolations, configuration)

    // previously marked resource
    whenever(resourceStateRepository.get(markedResourceWithViolations.resourceId, configuration.namespace)) doReturn
      ResourceState(
        deleted = false,
        markedResource = markedResourceWithViolations,
        statuses = mutableListOf(
          Status(name = Action.MARK.name, timestamp = clock.instant().minusMillis(3000).toEpochMilli())
        )
      )

    subject.handleEvents(event)

    verify(resourceTagger).unTag(
      markedResource = markedResourceWithViolations,
      workConfiguration = configuration,
      description = "Removing tag for now deleted ${event.markedResource.typeAndName()}"
    )

    // should have two statuses with DELETE being the latest
    verify(resourceStateRepository).upsert(
      argWhere {
        it.deleted && it.markedResource == markedResourceWithViolations && it.currentStatus!!.name == Action.DELETE.name &&
          it.statuses.size == 2 && it.statuses.first().name == Action.MARK.name &&
          it.currentStatus!!.timestamp > it.statuses.first().timestamp
      }
    )
  }

  @Test
  fun `should update state and untag resource when it's opted out`() {
    val event = OptOutResourceEvent(markedResourceWithViolations, configuration)

    // previously marked resource
    whenever(resourceStateRepository.get(markedResourceWithViolations.resourceId, configuration.namespace)) doReturn
      ResourceState(
        optedOut = false,
        markedResource = markedResourceWithViolations,
        statuses = mutableListOf(
          Status(name = Action.MARK.name, timestamp = clock.instant().minusMillis(3000).toEpochMilli())
        )
      )

    subject.handleEvents(event)

    verify(resourceTagger).unTag(
      markedResource = markedResourceWithViolations,
      workConfiguration = configuration,
      description = "${event.markedResource.typeAndName()}. Opted Out"
    )

    // should have two statuses with OPTOUT being the latest
    verify(resourceStateRepository).upsert(
      argWhere {
        it.optedOut
        && it.markedResource == markedResourceWithViolations
        && it.currentStatus!!.name == Action.OPTOUT.name
        && it.statuses.size == 2 && it.statuses.first().name == Action.MARK.name
        && it.currentStatus!!.timestamp > it.statuses.first().timestamp
      }
    )
  }

  @Test
  fun `should update state and tag when resource is soft deleted`() {
    val event = SoftDeleteResourceEvent(markedResourceWithViolations, configuration)

    // previously marked resource
    whenever(resourceStateRepository.get(markedResourceWithViolations.resourceId, configuration.namespace)) doReturn
      ResourceState(
        optedOut = false,
        markedResource = markedResourceWithViolations,
        statuses = mutableListOf(
          Status(name = Action.MARK.name, timestamp = clock.instant().minusMillis(3000).toEpochMilli())
        )
      )

    subject.handleEvents(event)

    verify(resourceTagger).tag(
      markedResource = markedResourceWithViolations,
      workConfiguration = configuration,
      description = "Soft deleted resource ${event.markedResource.typeAndName()}"
    )
    verify(resourceStateRepository).upsert(
      argWhere {
        it.softDeleted
          && it.markedResource == markedResourceWithViolations
          && it.currentStatus!!.name == Action.SOFTDELETE.name
          && it.statuses.size == 2 && it.statuses.first().name == Action.MARK.name
          && it.currentStatus!!.timestamp > it.statuses.first().timestamp
      }
    )
  }

  @Test
  fun `should update state when there was a task failure`() {
    val event = OrcaTaskFailureEvent(Action.DELETE, markedResourceWithViolations, configuration)

    // previously marked resource
    whenever(resourceStateRepository.get(markedResourceWithViolations.resourceId, configuration.namespace)) doReturn
      ResourceState(
        optedOut = false,
        markedResource = markedResourceWithViolations,
        statuses = mutableListOf(
          Status(name = Action.MARK.name, timestamp = clock.instant().minusMillis(3000).toEpochMilli())
        )
      )

    subject.handleEvents(event)

    verify(resourceTagger).tag(
      markedResource = markedResourceWithViolations,
      workConfiguration = configuration,
      description = subject.generateFailureMessage(event)
    )
    verify(resourceStateRepository).upsert(
      argWhere {
        !it.deleted
        && it.markedResource == markedResourceWithViolations
        && it.currentStatus!!.name.contains("FAILED", ignoreCase = true)
        && it.statuses.size == 2 && it.statuses.first().name == Action.MARK.name
        && it.currentStatus!!.timestamp > it.statuses.first().timestamp
      }
    )
  }
}
