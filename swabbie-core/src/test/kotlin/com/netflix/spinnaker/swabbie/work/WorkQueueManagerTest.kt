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

import com.netflix.appinfo.InstanceInfo.InstanceStatus.DOWN
import com.netflix.appinfo.InstanceInfo.InstanceStatus.UP
import com.netflix.discovery.StatusChangeEvent
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
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

  val workQueueManager = WorkQueueManager(
    dynamicConfigService = dynamicConfigService,
    swabbieProperties = SwabbieProperties(),
    queue = workQueue,
    clock = timeToWorkClock(),
    registry = NoopRegistry(),
    cacheStatus = NoopCacheStatus()
  )

  // StatusChangeEvent(previous, current)
  val upEvent = RemoteStatusChangedEvent(StatusChangeEvent(DOWN, UP))
  val downEvent = RemoteStatusChangedEvent(StatusChangeEvent(UP, DOWN))

  @BeforeEach
  fun reset() {
    workQueue.clear()
    reset(dynamicConfigService)
  }

  @Test
  fun `when down queue should not be filled`() {
    workQueueManager.onApplicationEvent(downEvent)
    workQueueManager.monitor()
    expectThat(workQueue.isEmpty()).isTrue()
  }

  @Test
  fun `when down queue should not be emptied`() {
    workQueueManager.onApplicationEvent(downEvent)
    workQueue.seed()
    workQueueManager.monitor()
    expectThat(workQueue.isEmpty()).isFalse()
  }

  @Test
  fun `when up and enabled queue should be filled`() {
    whenever(dynamicConfigService.isEnabled(SWABBIE_FLAG_PROPERY, false)) doReturn false

    workQueueManager.onApplicationEvent(upEvent)
    workQueueManager.monitor()

    expectThat(workQueue.isEmpty()).isFalse()
  }

  @Test
  fun `when up and disabled queue should be emptied`() {
    whenever(dynamicConfigService.isEnabled(SWABBIE_FLAG_PROPERY, false)) doReturn true

    workQueueManager.onApplicationEvent(upEvent)

    expectThat(workQueue.isEmpty()).isTrue()
  }

  private fun timeToWorkClock(): Clock {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 17, 0, 0) // tues at 10am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    return MutableClock(instant, zoneId)
  }
}
