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
import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.test.TestResource
import org.junit.jupiter.api.Test
import strikt.api.expect
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo

object AllowListExclusionPolicyTest {
  private val subject = AllowListExclusionPolicy()
  private val allowListType = ExclusionType.Allowlist.name

  @Test
  fun `should exclude if not Allow List`() {
    val exclusions = listOf(
      Exclusion()
        .withType(allowListType)
        .withAttributes(
          setOf(
            Attribute()
              .withKey("name")
              .withValue(
                listOf("testapp-v001", "pattern:^important")
              )
          )
        )
    )

    val resource1 = TestResource("testapp-v001")
    val resource2 = TestResource("important-v001")
    val resource3 = TestResource("test-v001")

    val resources = listOf(resource1, resource2, resource3)

    val result = resources
      .filter {
        subject.apply(it, exclusions) == null
      }

    expect {
      that(result.size).isEqualTo(2)
      that(result).containsExactly(resource1, resource2)
    }
  }

  @Test
  fun `should include if in one of the allow lists`() {
    val ownerField = "swabbieResourceOwner"
    val resource1 = TestResource("testapp-v001").withDetail(ownerField, "bla@netflix.com")
    val resource2 = TestResource("grpclab-v001").withDetail(ownerField, "notbla@netflix.com")
    val resource3 = TestResource("test-v001").withDetail(ownerField, "sobla@netflix.com")
    val resources = listOf(resource1, resource2, resource3)

    val exclusions = listOf(
      Exclusion()
        .withType(allowListType)
        .withAttributes(
          setOf(
            Attribute()
              .withKey(ownerField)
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

    val result = resources.filter {
      subject.apply(it, exclusions) == null
    }

    expect {
      that(result.size).isEqualTo(2)
      that(result).containsExactly(resource1, resource2)
    }
  }
}
