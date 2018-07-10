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
import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

object Front50ApplicationExclusionPolicyTest {
  private val front50ApplicationCache: InMemoryCache<Application> = mock()

  @Test
  fun `should exclude by application name & email`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Application.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("name")
              .withValue(
                listOf("testapp")
              ),
            Attribute()
              .withKey("email")
              .withValue(
                listOf("test@netflix.com")
              )
          )
        )
    )

    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "testapp", email = "name@netflix.com"),
        Application(name = "test", email = "test@netflix.com"),
        Application(name = "random", email = "random@netflix.com")
      )


    val resources = listOf(
      TestResource("testapp-v001"),
      TestResource("test-v001"),
      TestResource("random")
    )

    resources.filter {
      Front50ApplicationExclusionPolicy(front50ApplicationCache).apply(it, exclusions) == null
    }.let { filteredResources ->
      assertEquals(1, filteredResources.size, "excluded one by name and the other by email")
      filteredResources.first().resourceId shouldMatch equalTo("random")
      filteredResources.size shouldMatch equalTo(1)
    }
  }
}
