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
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.ResourceTypeHandlerTest.workConfiguration
import com.netflix.spinnaker.swabbie.WorkConfigurator
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.Test
import java.time.Clock

object NotificationAgentTest {
  private val clock = Clock.systemDefaultZone()
  private val onCompleteCallback = {}
  private val workConfigurator = mock<WorkConfigurator>()
  private val agentExecutor = BlockingThreadExecutor()

  @Test
  fun `should not notify if no handler found`() {
    val configuration = workConfiguration()
    val resourceTypeHandler = mock<ResourceTypeHandler<*>>()
    whenever(resourceTypeHandler.handles(configuration)) doReturn false

    NotificationAgent(
      registry = NoopRegistry(),
      discoverySupport = mock(),
      clock = clock,
      resourceTypeHandlers = listOf(resourceTypeHandler),
      workConfigurator = workConfigurator,
      agentExecutor = agentExecutor
    ).process(configuration, onCompleteCallback)

    verify(resourceTypeHandler, never()).notify(any(), any())
  }

  @Test
  fun `should notify`() {
    val configuration = workConfiguration()
    val resourceTypeHandler = mock<ResourceTypeHandler<*>>()

    whenever(resourceTypeHandler.handles(configuration)) doReturn true
    NotificationAgent(
      registry = NoopRegistry(),
      discoverySupport = mock(),
      resourceTypeHandlers = listOf(resourceTypeHandler),
      clock = clock,
      workConfigurator = workConfigurator,
      agentExecutor = agentExecutor
    ).process(configuration, onCompleteCallback)

    verify(resourceTypeHandler, atMost(maxNumberOfInvocations = 1)).notify(
      argWhere { it == configuration }, any()
    )
  }
}
