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

package com.netflix.spinnaker.swabbie.front50

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.Grouping
import com.netflix.spinnaker.swabbie.model.GroupingType
import com.netflix.spinnaker.swabbie.test.TestResource
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

object ApplicationResourceOwnerResolutionStrategyTest {
  private val front50ApplicationCache: InMemoryCache<Application> = mock()
  private val subject = ApplicationResourceOwnerResolutionStrategy(front50ApplicationCache, NoopRegistry())

  @Test
  fun `should resolve resource owner from spinnaker application`() {
    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "name", email = "name@netflix.com"),
        Application(name = "test", email = "test@netflix.com")
      )

    subject.resolve(
      TestResource(
        resourceId = "id",
        name = "name",
        grouping = Grouping("name", GroupingType.APPLICATION)
      )
    ) shouldMatch equalTo("name@netflix.com")
  }
}
