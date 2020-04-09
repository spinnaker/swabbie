/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.work

import com.netflix.spinnaker.config.Schedule
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isFalse
import strikt.assertions.isTrue

object WorkQueueManagerScheduleTest {

  @Test
  fun `should handle days according to schedule`() {
    val zoneId = ZoneId.of("America/Los_Angeles")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 17, 10, 0, 0) // a monday
    val instant = dateTime.toInstant(ZoneOffset.of("-7"))
    val clock = Clock.fixed(instant, zoneId)

    expectThat(LocalDate.now(clock).dayOfWeek == DayOfWeek.MONDAY).isTrue()

    val schedule = Schedule()
    schedule.allowedDaysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
    expectThat(WorkQueueManager.timeToWork(schedule, clock)).isTrue()

    schedule.allowedDaysOfWeek = listOf(DayOfWeek.TUESDAY)
    expectThat(WorkQueueManager.timeToWork(schedule, clock)).isFalse()

    // startTime > endTime
    schedule.startTime = "20:00"
    schedule.endTime = "07:00"
    schedule.allowedDaysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)

    expectThrows<IllegalStateException> { WorkQueueManager.timeToWork(schedule, clock) }
  }

  @Test
  fun `monday 8 am not work time for normal schedule`() {
    val zoneId = ZoneId.of("America/Los_Angeles")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 17, 16, 0, 0) // a monday
    val instant = dateTime.toInstant(ZoneOffset.of("-7"))
    val clock = Clock.fixed(instant, zoneId)

    expectThat(WorkQueueManager.timeToWork(Schedule(), clock)).isFalse()
  }

  @Test
  fun `handle clock in UTC, work tues at 10am`() {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 17, 0, 0) // tues at 10am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    val clock = Clock.fixed(instant, zoneId)

    expectThat(WorkQueueManager.timeToWork(Schedule(), clock)).isTrue()
  }

  @Test
  fun `handle clock in UTC, don't work tues at 8am`() {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 15, 0, 0) // tues at 8am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    val clock = Clock.fixed(instant, zoneId)

    expectThat(WorkQueueManager.timeToWork(Schedule(), clock)).isFalse()
  }
}
