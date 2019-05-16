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
package com.netflix.spinnaker.swabbie.exclusions
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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

object AllowListExclusionPolicyTest {
  private val front50ApplicationCache: InMemoryCache<Application> = mock()

  @Test
  fun `should exclude based on composite key if not in Allow List`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Allowlist.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("application.name")
              .withValue(
                listOf("testapp", "pattern:^important")
              )
          )
        )
    )
    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "testapp", email = "name@netflix.com"),
        Application(name = "important", email = "test@netflix.com"),
        Application(name = "random", email = "random@netflix.com")
      )
    val resources = listOf(
      TestResource("testapp-v001"),
      TestResource("important-v001"),
      TestResource("test-v001")
    )
    resources.filter {
      AllowListExclusionPolicy(front50ApplicationCache, mock()).apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(2)
      filteredResources.map { it.resourceId }.let {
        assertTrue(it.contains("important-v001"), "Allow List by pattern")
        assertTrue(it.contains("testapp-v001"), "Allow List by name")
      }
      filteredResources.first().resourceId shouldMatch equalTo("testapp-v001")
    }
  }

  @Test
  fun `should exclude if not Allow List`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Allowlist.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("name")
              .withValue(
                listOf("testapp-v001", "pattern:^important")
              )
          )
        )
    )
    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "testapp", email = "name@netflix.com"),
        Application(name = "important", email = "test@netflix.com"),
        Application(name = "random", email = "random@netflix.com")
      )
    val resources = listOf(
      TestResource("testapp-v001"),
      TestResource("important-v001"),
      TestResource("test-v001")
    )
    resources.filter {
      AllowListExclusionPolicy(front50ApplicationCache, mock()).apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(2)
      filteredResources.map { it.resourceId }.let {
        Assertions.assertTrue(it.contains("important-v001"), "Allow List by pattern")
        Assertions.assertTrue(it.contains("testapp-v001"), "Allow List by name")
      }
      filteredResources.first().resourceId shouldMatch equalTo("testapp-v001")
    }
  }

  @Test
  fun `should include if in one of the allow lists`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Allowlist.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("swabbieResourceOwner")
              .withValue(
                listOf("bla@netflix.com", "bla2@netflix.com")
              ),
            Attribute()
              .withKey("name")
              .withValue(
                listOf("pattern:^grpc.*\$")
              )
          )
        )
    )
    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "testapp", email = "bla@netflix.com"),
        Application(name = "important", email = "test@netflix.com"),
        Application(name = "random", email = "random@netflix.com")
      )
    val resources = listOf(
      TestResource("testapp-v001")
        .withDetail("swabbieResourceOwner", "bla@netflix.com"),
      TestResource("grpclab-v001")
        .withDetail("swabbieResourceOwner", "notbla@netflix.com"),
      TestResource("test-v001")
        .withDetail("swabbieResourceOwner", "sobla@netflix.com")
    )
    resources.filter {
      AllowListExclusionPolicy(front50ApplicationCache, mock()).apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(2)
      filteredResources.map { it.resourceId }.let {
        assertTrue(it.contains("grpclab-v001"), "Allow List by pattern")
        assertTrue(it.contains("testapp-v001"), "Allow List by owner")
      }
      filteredResources.first().resourceId shouldMatch equalTo("testapp-v001")
    }
  }
}
