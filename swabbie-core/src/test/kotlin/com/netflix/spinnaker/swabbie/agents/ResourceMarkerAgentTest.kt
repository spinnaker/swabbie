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

package com.netflix.spinnaker.swabbie.agents

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.swabbie.LockManager
import com.netflix.spinnaker.swabbie.ResourceHandler
import com.netflix.spinnaker.swabbie.ResourceHandlerTest.workConfiguration
import com.netflix.spinnaker.swabbie.model.Work
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object ResourceMarkerAgentTest {
  private val clock = Clock.systemDefaultZone()
  private val lockManager = mock<LockManager>()
  private val executor = AgentExecutor(BlockingThreadExecutor())

  @AfterEach
  fun cleanup() {
    reset(lockManager)
  }

  @Test
  fun `should do nothing if lock cannot be acquired`() {
    val resourceHandler = mock<ResourceHandler<*>>()
    whenever(lockManager.acquire(any(), any())) doReturn false
    whenever(resourceHandler.handles(any(), any())) doReturn true

    ResourceMarkerAgent(
      clock = clock,
      registry = NoopRegistry(),
      discoverySupport = mock(),
      executor = executor,
      lockManager = lockManager,
      work = emptyList(),
      resourceHandlers = listOf(resourceHandler)
    ).run()

    verify(resourceHandler, never()).mark(any(), any())
  }

  @Test
  fun `should do nothing if there is no work to do`() {
    val resourceHandler = mock<ResourceHandler<*>>()
    whenever(lockManager.acquire(any(), any())) doReturn true
    whenever(resourceHandler.handles(any(), any())) doReturn true

    ResourceMarkerAgent(
      clock = clock,
      registry = NoopRegistry(),
      discoverySupport = mock(),
      executor = executor,
      lockManager = lockManager,
      work = emptyList(),
      resourceHandlers = listOf(resourceHandler)
    ).run()

    verify(resourceHandler, never()).mark(any(), any())
  }

  @Test
  fun `should find and dispatch work to a handler`() {
    val resourceHandler = mock<ResourceHandler<*>>()
    val configuration = workConfiguration()
    val work = listOf(
      Work(configuration.namespace, workConfiguration())
    )

    whenever(lockManager.acquire(any(), any())) doReturn true
    whenever(resourceHandler.handles(any(), any())) doReturn true

    ResourceMarkerAgent(
      clock = clock,
      registry = NoopRegistry(),
      discoverySupport = mock(),
      executor = executor,
      lockManager = lockManager,
      work = work,
      resourceHandlers = listOf(resourceHandler)
    ).run()

    verify(resourceHandler).mark(any(), any())
  }
}

internal class BlockingThreadExecutor: Executor {
  private val delegate = Executors.newSingleThreadExecutor()
  override fun execute(command: Runnable) {
    val latch = CountDownLatch(1)
    delegate.execute {
      try {
        command.run()
      } finally {
        latch.countDown()
      }
    }
    latch.await()
  }
}
