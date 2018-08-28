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

import com.fasterxml.jackson.annotation.JsonTypeName
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.Resource
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

object ApplicationResourceOwnerResolutionStrategyTest {
  private val front50ApplicationCache: InMemoryCache<Application> = mock()
  private val kriegerApplicationCache: InMemoryCache<Application> = mock()
  private val subject = ApplicationResourceOwnerResolutionStrategy(front50ApplicationCache, kriegerApplicationCache)

  @Test
  fun `should resolve resource owner from spinnaker application`() {
    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "name", email = "name@netflix.com"),
        Application(name = "test", email = "test@netflix.com")
      )

    subject.resolve(ResourceOne()) shouldMatch equalTo("name@netflix.com")
  }

  @Test
  fun `should resolve resource owner from krieger application`() {
    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "blahApp", email = "name@netflix.com"),
        Application(name = "testApp", email = "test@netflix.com")
      )

    // krieger applications have relationships to other resources. ie: images associated with this applications
    whenever(kriegerApplicationCache.get()) doReturn
      setOf(
        Application(name = "randomApplication", email = "kriegerOwner@netflix.com").apply {
          set("images", listOf(mapOf("id" to "ami-123")))
        },
        Application(name = "randomApplication 2", email = "test@netflix.com")
      )

    subject.resolve(ResourceTwo()) shouldMatch equalTo("kriegerOwner@netflix.com")
    Assertions.assertNull(subject.resolve(ResourceOne()))
  }

  @Test
  fun `should resolve all resource owners matched from a list of apps`() {
    whenever(kriegerApplicationCache.get()) doReturn
      setOf(
        Application(name = "randomApplication", email = "kriegerOwner@netflix.com").apply {
          set("images", listOf(mapOf("id" to "ami-123")))
        },
        Application(name = "randomApplication 2", email = "test@netflix.com").apply {
          set("images", listOf(mapOf("id" to "ami-123")))
        }
      )

    subject.resolve(ResourceTwo()) shouldMatch equalTo("kriegerOwner@netflix.com,test@netflix.com")
  }
}

@JsonTypeName("R")
data class ResourceOne(
  override val resourceId: String = "id",
  override val name: String = "name",
  override val resourceType: String = "type",
  override val cloudProvider: String = "provider",
  override val createTs: Long = System.currentTimeMillis()
) : Resource()

@JsonTypeName("R")
data class ResourceTwo(
  override val resourceId: String = "ami-123",
  override val name: String = "amiName",
  override val resourceType: String = IMAGE,
  override val cloudProvider: String = "provider",
  override val createTs: Long = System.currentTimeMillis()
) : Resource()

