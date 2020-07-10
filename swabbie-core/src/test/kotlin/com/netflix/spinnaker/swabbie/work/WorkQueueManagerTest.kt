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
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.discovery.DiscoveryStatusChangeEvent
import com.netflix.spinnaker.kork.discovery.DiscoveryStatusListener
import com.netflix.spinnaker.kork.discovery.InstanceStatus
import com.netflix.spinnaker.kork.discovery.RemoteStatusChangedEvent
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.kork.test.time.MutableClock
import com.netflix.spinnaker.swabbie.test.InMemoryWorkQueue
import com.netflix.spinnaker.swabbie.test.NoopCacheStatus
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.whenever
import java.time.Clock
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue

object WorkQueueManagerTest {

  val dynamicConfigService: DynamicConfigService = mock()
  val workConfiguration = WorkConfigurationTestHelper.generateWorkConfiguration()
  val workQueue = InMemoryWorkQueue(listOf(workConfiguration))
  val discoveryStatusListener = mock<DiscoveryStatusListener>()

  val workQueueManager = WorkQueueManager(
    dynamicConfigService = dynamicConfigService,
    swabbieProperties = SwabbieProperties(),
    queue = workQueue,
    clock = timeToWorkClock(),
    registry = NoopRegistry(),
    cacheStatus = NoopCacheStatus(),
    discoveryStatusListener = discoveryStatusListener
  )

  // StatusChangeEvent(previous, current)
  val upEvent = RemoteStatusChangedEvent(DiscoveryStatusChangeEvent(InstanceStatus.UNKNOWN, InstanceStatus.UP))
  val downEvent = RemoteStatusChangedEvent(DiscoveryStatusChangeEvent(InstanceStatus.UP, InstanceStatus.DOWN))

  @BeforeEach
  fun reset() {
    workQueue.clear()
    reset(dynamicConfigService)
    reset(discoveryStatusListener)
  }

  @Test
  fun `when down queue should not be filled`() {
    whenever(discoveryStatusListener.isEnabled) doReturn false
    workQueueManager.onApplicationEvent(downEvent)
    workQueueManager.monitor()
    expectThat(workQueue.isEmpty()).isTrue()
  }

  @Test
  fun `when down queue should not be emptied`() {
    whenever(discoveryStatusListener.isEnabled) doReturn false
    workQueueManager.onApplicationEvent(downEvent)
    workQueue.seed()
    workQueueManager.monitor()
    expectThat(workQueue.isEmpty()).isFalse()
  }

  @Test
  fun `when up and enabled queue should be filled`() {
    whenever(dynamicConfigService.isEnabled(SWABBIE_FLAG_PROPERY, false)) doReturn false
    whenever(discoveryStatusListener.isEnabled) doReturn true

    workQueueManager.onApplicationEvent(upEvent)
    workQueueManager.monitor()

    expectThat(workQueue.isEmpty()).isFalse()
  }

  @Test
  fun `when up and disabled queue should be emptied`() {
    whenever(dynamicConfigService.isEnabled(SWABBIE_FLAG_PROPERY, false)) doReturn true
    whenever(discoveryStatusListener.isEnabled) doReturn true

    workQueueManager.onApplicationEvent(upEvent)
    workQueueManager.monitor()

    expectThat(workQueue.isEmpty()).isTrue()
  }

  private fun timeToWorkClock(): Clock {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 17, 0, 0) // tues at 10am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    return MutableClock(instant, zoneId)
  }
}
