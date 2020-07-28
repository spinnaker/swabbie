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
import com.netflix.spinnaker.swabbie.model.Grouping
import com.netflix.spinnaker.swabbie.model.GroupingType
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

object Front50ApplicationExclusionPolicyTest {
  private val front50ApplicationCache: InMemoryCache<Application> = mock()

  @Test
  fun `should exclude by application name & email`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Application.toString())
        .withAttributes(
          setOf(
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
      TestResource("testapp-v001", grouping = Grouping("testapp", GroupingType.APPLICATION)),
      TestResource("test-v001", grouping = Grouping("test", GroupingType.APPLICATION)),
      TestResource("random", grouping = Grouping("random", GroupingType.APPLICATION))
    )

    resources.filter {
      Front50ApplicationExclusionPolicy(front50ApplicationCache).apply(it, exclusions) == null
    }.let { filteredResources ->
      assertEquals(1, filteredResources.size, "excluded one by name and the other by email")
      filteredResources.first().resourceId shouldMatch equalTo("random")
    }
  }

  @Test
  fun `should exclude based on pattern`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Application.toString())
        .withAttributes(
          setOf(
            Attribute()
              .withKey("name")
              .withValue(
                listOf("pattern:^cloud")
              )
          )
        )
    )

    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "clouddriver", email = "name@netflix.com"),
        Application(name = "acloud", email = "test@netflix.com"),
        Application(name = "wowcloudwow", email = "random@netflix.com")
      )

    val resources = listOf(
      TestResource("clouddriver-v001", grouping = Grouping("clouddriver", GroupingType.APPLICATION)),
      TestResource("acloud-v001", grouping = Grouping("acloud", GroupingType.APPLICATION)),
      TestResource("wowcloudwow", grouping = Grouping("wowcloudwow", GroupingType.APPLICATION))
    )

    resources.filter {
      Front50ApplicationExclusionPolicy(front50ApplicationCache).apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(2)
    }
  }

  @Test
  fun `should ignore images`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Application.toString())
        .withAttributes(
          setOf(
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
      TestResource("my-package-0.0.1", grouping = Grouping("my-package", GroupingType.PACKAGE_NAME))
    )

    resources.filter {
      Front50ApplicationExclusionPolicy(front50ApplicationCache).apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(1) // it doesn't get excluded
    }
  }
}
