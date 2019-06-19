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
package com.netflix.spinnaker.swabbie.model

import com.netflix.spinnaker.swabbie.events.Action
import org.junit.jupiter.api.Test

object WorkConfigurationTest {
  @Test
  fun `Should expand configuration to work items`() {
    val workConfiguration = WorkConfiguration(
      namespace = "workConfiguration1",
      account = SpinnakerAccount(
        name = "test",
        accountId = "id",
        type = "type",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      ),
      location = "us-east-1",
      cloudProvider = AWS,
      resourceType = "testResourceType",
      retention = 14,
      exclusions = emptySet(),
      maxAge = 1
    )

    val workItems = workConfiguration.toWorkItems()
    assert(workItems.size == 3)

    with(workItems) {
      assert(listOf(Action.MARK, Action.NOTIFY, Action.DELETE) == map { it.action })
      assert(map { it.workConfiguration }.toSet().size == 1)
      assert(setOf(workConfiguration) == map { it.workConfiguration }.toSet())
    }
  }
}
