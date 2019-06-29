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

import com.netflix.spinnaker.config.Schedule
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.DayOfWeek

object WorkQueueManagerTest {

  @Test
  fun `should handle days according to schedule`() {
    val zoneId = ZoneId.of("America/Los_Angeles")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 17, 10, 0, 0) // a monday
    val instant = dateTime.toInstant(ZoneOffset.of("-7"))
    val clock = Clock.fixed(instant, zoneId)

    assert(LocalDate.now(clock).dayOfWeek == DayOfWeek.MONDAY)

    val schedule = Schedule()
    schedule.allowedDaysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
    assert(WorkQueueManager.timeToWork(schedule, clock))

    schedule.allowedDaysOfWeek = listOf(DayOfWeek.TUESDAY)
    assert(!WorkQueueManager.timeToWork(schedule, clock))

    // startTime > endTime
    schedule.startTime = "20:00"
    schedule.endTime = "07:00"
    schedule.allowedDaysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)

    Assertions.assertThrows(IllegalStateException::class.java, {
      WorkQueueManager.timeToWork(schedule, clock)
    }, "Scheduled startTime: ${schedule.startTime} cannot be after endTime: ${schedule.endTime}")
  }

  @Test
  fun `monday 8 am not work time for normal schedule`() {
    val zoneId = ZoneId.of("America/Los_Angeles")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 17, 16, 0, 0) // a monday
    val instant = dateTime.toInstant(ZoneOffset.of("-7"))
    val clock = Clock.fixed(instant, zoneId)

    assert(!WorkQueueManager.timeToWork(Schedule(), clock))
  }

  @Test
  fun `handle clock in UTC, work tues at 10am`() {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 17, 0, 0) // tues at 10am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    val clock = Clock.fixed(instant, zoneId)

    assert(WorkQueueManager.timeToWork(Schedule(), clock))
  }

  @Test
  fun `handle clock in UTC, don't work tues at 8am`() {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 15, 0, 0) // tues at 8am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    val clock = Clock.fixed(instant, zoneId)

    assert(!WorkQueueManager.timeToWork(Schedule(), clock))
  }
}
