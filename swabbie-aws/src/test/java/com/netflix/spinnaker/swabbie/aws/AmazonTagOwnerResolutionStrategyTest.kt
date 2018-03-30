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

package com.netflix.spinnaker.swabbie.aws

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.swabbie.aws.exclusions.AwsTestResource
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

object AmazonTagOwnerResolutionStrategyTest {
  @Test
  fun `should resolve owner from aws tag if present`() {
    var resource = AwsTestResource("1")
      .withDetail(name = "tags", value = listOf(mapOf("owner" to "test@netflix.com")))


    AmazonTagOwnerResolutionStrategy().resolve(resource as AmazonResource).let { owner ->
      owner shouldMatch equalTo("test@netflix.com")
    }

    resource = AwsTestResource("1")
    AmazonTagOwnerResolutionStrategy().resolve(resource as AmazonResource).let { owner ->
      Assertions.assertNull(owner)
    }
  }
}
