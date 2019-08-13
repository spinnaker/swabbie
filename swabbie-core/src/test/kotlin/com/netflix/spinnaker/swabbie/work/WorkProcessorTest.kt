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

package com.netflix.spinnaker.swabbie.work

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.test.InMemoryWorkQueue
import com.netflix.spinnaker.swabbie.test.NoopCacheStatus
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.whenever
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.never
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock

object WorkProcessorTest {
  private val workConfiguration1 = WorkConfiguration(
    namespace = "workConfiguration1",
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

  private val workConfiguration2 = WorkConfiguration(
    namespace = "workConfiguration2",
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

  private val workQueue = InMemoryWorkQueue(_seed = listOf(workConfiguration1, workConfiguration2))
  private val handler1 = mock<ResourceTypeHandler<TestResource>>()
  private val handler2 = mock<ResourceTypeHandler<TestResource>>()

  private val processor = WorkProcessor(
    clock = Clock.systemDefaultZone(),
    registry = NoopRegistry(),
    resourceTypeHandlers = listOf(handler1, handler2),
    workQueue = workQueue,
    lockingService = LockingService.NOOP(),
    cacheStatus = NoopCacheStatus()
  )

  @BeforeEach
  fun setup() {
    workQueue.clear()
    reset(handler1, handler2)
  }

  @Test
  fun `should do nothing if work queue is empty`() {
    whenever(handler1.handles(any())) doReturn true
    whenever(handler2.handles(any())) doReturn true

    processor.process()

    verify(handler1, never()).notify(any())
    verify(handler1, never()).delete(any())
    verify(handler1, never()).mark(any())

    verify(handler2, never()).notify(any())
    verify(handler2, never()).delete(any())
    verify(handler2, never()).mark(any())
  }

  @Test
  fun `should process work present on the queue`() {
    // insert work items from _seed
    workQueue.seed()

    whenever(handler1.handles(workConfiguration1)) doReturn true
    whenever(handler2.handles(workConfiguration2)) doReturn true

    processor.process()

    verify(handler1).mark(workConfiguration1)
    verify(handler1).notify(workConfiguration1)
    verify(handler1).delete(workConfiguration1)

    verify(handler2).mark(workConfiguration2)
    verify(handler2).notify(workConfiguration2)
    verify(handler2).delete(workConfiguration2)

    assert(workQueue.isEmpty())
  }

  @Test
  fun `should handle a specific work action MARK, NOTIFY or DELETE`() {
    // only include mark work items in the queue to be handled by both handlers
    workConfiguration1.toWorkItems().forEach {
      if (it.action == Action.MARK) {
        workQueue.push(it)
        whenever(handler1.handles(it.workConfiguration)) doReturn true
      }
    }

    workConfiguration2.toWorkItems().forEach {
      if (it.action == Action.MARK) {
        workQueue.push(it)
        whenever(handler2.handles(it.workConfiguration)) doReturn true
      }
    }

    processor.process()

    verify(handler1).mark(workConfiguration1)
    verify(handler2).mark(workConfiguration2)

    verify(handler1, never()).notify(any())
    verify(handler1, never()).delete(any())
    verify(handler2, never()).notify(workConfiguration2)
    verify(handler2, never()).delete(workConfiguration2)

    assert(workQueue.isEmpty())
  }
}
