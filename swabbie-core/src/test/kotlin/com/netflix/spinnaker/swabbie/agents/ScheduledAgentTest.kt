package com.netflix.spinnaker.swabbie.agents

import com.netflix.spinnaker.config.Schedule
import org.junit.jupiter.api.Test
import java.time.*


object ScheduledAgentTest {

  @Test
  fun `monday 10 am is work time for normal schedule`() {
    val zoneId = ZoneId.of("America/Los_Angeles")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 17, 10, 0, 0) // a monday
    val instant = dateTime.toInstant(ZoneOffset.of("-7"))
    val clock = Clock.fixed(instant, zoneId)

    assert(ScheduledAgent.timeToWork(Schedule(), clock))
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

  @Test
  fun `handle working all night (end time before start time)`() {
    val zoneId = ZoneId.of("UTC")
    val dateTime = LocalDateTime.of(2018, Month.SEPTEMBER, 18, 12, 0, 0) // tues at 5am PST, in UTC
    val instant = dateTime.toInstant(ZoneOffset.UTC)
    val clock = Clock.fixed(instant, zoneId)

    val schedule = Schedule()
    schedule.startTime = "21:00"
    schedule.endTime = "07:00"

    assert(ScheduledAgent.timeToWork(schedule, clock))
  }

}
