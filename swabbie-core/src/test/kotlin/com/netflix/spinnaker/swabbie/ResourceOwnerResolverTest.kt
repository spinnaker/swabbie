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

package com.netflix.spinnaker.swabbie

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.INSTANCE
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.test.TestResource
import org.junit.jupiter.api.Test

object ResourceOwnerResolverTest {
  private val resource = TestResource("1", resourceType = IMAGE)
  private val secondResource = TestResource("2", resourceType = INSTANCE)

  @Test
  fun `should resolve to single owner`() {
    val subject = ResourceOwnerResolver(NoopRegistry(), listOf(ConstantStrategy()))
    subject.resolve(resource) shouldMatch equalTo("swabbie@swabbie.io")
  }

  @Test
  fun `should resolve to null owner`() {
    val subject = ResourceOwnerResolver(NoopRegistry(), listOf(NoopStrategy()))
    assert(subject.resolve(resource) == null)
  }

  @Test
  fun `should pick primary owner`() {
    val subject = ResourceOwnerResolver(NoopRegistry(), listOf(ConstantStrategy(), TestStrategy()))
    subject.resolve(resource) shouldMatch equalTo("test@netflix.com")
  }

  @Test
  fun `should return multiple owners`() {
    val subject = ResourceOwnerResolver(NoopRegistry(), listOf(ConstantStrategy(), TestStrategy()))
    subject.resolve(secondResource) shouldMatch equalTo("swabbie@swabbie.io,ohwow@netflix.com")
  }
}

class NoopStrategy : ResourceOwnerResolutionStrategy<Resource> {
  override fun resolve(resource: Resource): String? = null
  override fun primaryFor(): Set<String> = emptySet()
}

class ConstantStrategy : ResourceOwnerResolutionStrategy<Resource> {
  override fun resolve(resource: Resource): String? = "swabbie@swabbie.io"
  override fun primaryFor(): Set<String> = emptySet()
}

class TestStrategy : ResourceOwnerResolutionStrategy<Resource> {
  override fun resolve(resource: Resource): String? {
    return if (resource.resourceId == "1") {
      "test@netflix.com"
    } else {
      "ohwow@netflix.com"
    }
  }
  override fun primaryFor(): Set<String> = setOf("image")
}
