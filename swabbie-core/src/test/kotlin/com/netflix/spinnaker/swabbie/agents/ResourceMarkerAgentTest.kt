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
  private val configuration = workConfiguration()

  @AfterEach
  fun cleanup() {
    reset(lockManager)
  }

  @Test
  fun `should do nothing if no handler is found for configuration`() {
    val resourceHandler = mock<ResourceHandler<*>>()
    whenever(resourceHandler.handles(configuration)) doReturn false

    ResourceMarkerAgent(
      clock = clock,
      registry = NoopRegistry(),
      workProcessor = mock(),
      discoverySupport = mock(),
      executor = executor,
      resourceHandlers = listOf(resourceHandler)
    ).process(configuration)

    verify(resourceHandler, never()).mark(any(), any())
  }

  @Test
  fun `should find and dispatch work to a handler`() {
    val resourceHandler = mock<ResourceHandler<*>>()
    whenever(resourceHandler.handles(configuration)) doReturn true

    ResourceMarkerAgent(
      clock = clock,
      registry = NoopRegistry(),
      workProcessor = mock(),
      discoverySupport = mock(),
      executor = executor,
      resourceHandlers = listOf(resourceHandler)
    ).process(ResourceMarkerAgentTest.configuration)

    verify(resourceHandler).mark(any(), any())
  }
}

internal class BlockingThreadExecutor : Executor {
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
