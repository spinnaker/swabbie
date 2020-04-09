/*
 *
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.config.Schedule
import java.time.Instant
import org.junit.jupiter.api.Test

object ScheduleTest {
  private val subject = Schedule().also { it.enabled = true }

  val mondayTimestamp = Instant.parse("2018-05-21T12:00:00Z")
  val satTimestamp = Instant.parse("2018-05-26T12:00:00Z")
  val followingMondayTimestamp = Instant.parse("2018-05-28T12:00:00Z")

  @Test
  fun `should return timestamp when day is in the schedule`() {
    subject.getNextTimeInWindow(mondayTimestamp.toEpochMilli()) shouldMatch equalTo(mondayTimestamp.toEpochMilli())
  }

  @Test
  fun `should return monday when saturday is the proposed timestamp`() {
    subject.getNextTimeInWindow(satTimestamp.toEpochMilli()) shouldMatch equalTo(followingMondayTimestamp.toEpochMilli())
  }
}
