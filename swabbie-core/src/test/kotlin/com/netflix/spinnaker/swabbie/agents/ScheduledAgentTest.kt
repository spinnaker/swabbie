package com.netflix.spinnaker.swabbie.agents

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

object ScheduledAgentTest {

  @Test
  fun `should handle days according to schedule`() {
    val zoneId = ZoneId.of("America/Los_Angeles")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 17, 10, 0, 0) // a monday
    val instant = dateTime.toInstant(ZoneOffset.of("-7"))
    val clock = Clock.fixed(instant, zoneId)

    assert(LocalDate.now(clock).dayOfWeek == DayOfWeek.MONDAY)

    val schedule = Schedule()
    schedule.allowedDaysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
    assert(ScheduledAgent.timeToWork(schedule, clock))

    schedule.allowedDaysOfWeek = listOf(DayOfWeek.TUESDAY)
    assert(!ScheduledAgent.timeToWork(schedule, clock))

    // startTime > endTime
    schedule.startTime = "20:00"
    schedule.endTime = "07:00"
    schedule.allowedDaysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)

    Assertions.assertThrows(IllegalStateException::class.java, {
      ScheduledAgent.timeToWork(schedule, clock)
    }, "Scheduled startTime: ${schedule.startTime} cannot be after endTime: ${schedule.endTime}")
  }

  @Test
  fun `monday 8 am not work time for normal schedule`() {
    val zoneId = ZoneId.of("America/Los_Angeles")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 17, 16, 0, 0) // a monday
    val instant = dateTime.toInstant(ZoneOffset.of("-7"))
    val clock = Clock.fixed(instant, zoneId)

    assert(!ScheduledAgent.timeToWork(Schedule(), clock))
  }

  @Test
  fun `handle clock in UTC, work tues at 10am`() {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 17, 0, 0) // tues at 10am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    val clock = Clock.fixed(instant, zoneId)

    assert(ScheduledAgent.timeToWork(Schedule(), clock))
  }

  @Test
  fun `handle clock in UTC, don't work tues at 8am`() {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 15, 0, 0) // tues at 8am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    val clock = Clock.fixed(instant, zoneId)

    assert(!ScheduledAgent.timeToWork(Schedule(), clock))
  }
}
