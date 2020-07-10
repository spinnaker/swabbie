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
import com.netflix.spinnaker.kork.discovery.DiscoveryStatusListener
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.test.InMemoryWorkQueue
import com.netflix.spinnaker.swabbie.test.NoopCacheStatus
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import java.time.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue

object WorkProcessorTest {
  private val workConfiguration1 = WorkConfigurationTestHelper
    .generateWorkConfiguration(namespace = "workConfiguration1")

  private val workConfiguration2 = WorkConfigurationTestHelper
    .generateWorkConfiguration(namespace = "workConfiguration2")

  private val workQueue = InMemoryWorkQueue(_seed = listOf(workConfiguration1, workConfiguration2))
  private val handler1 = mock<ResourceTypeHandler<TestResource>>()
  private val handler2 = mock<ResourceTypeHandler<TestResource>>()
  private val discoveryStatusListener = mock<DiscoveryStatusListener>()

  private val processor = WorkProcessor(
    clock = Clock.systemDefaultZone(),
    registry = NoopRegistry(),
    resourceTypeHandlers = listOf(handler1, handler2),
    workQueue = workQueue,
    lockingService = LockingService.NOOP(),
    cacheStatus = NoopCacheStatus(),
    discoveryStatusListener = discoveryStatusListener
  )

  @BeforeEach
  fun setup() {
    workQueue.clear()
    reset(handler1, handler2)
    whenever(discoveryStatusListener.isEnabled) doReturn true
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

    expectThat(workQueue.isEmpty()).isTrue()
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

    expectThat(workQueue.isEmpty()).isTrue()
  }

  @Test
  fun `should not process work when disabled`() {
    whenever(discoveryStatusListener.isEnabled) doReturn false
    workQueue.seed()
    processor.process()

    expectThat(workQueue.isEmpty()).isFalse()
  }
}
