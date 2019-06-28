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
import com.netflix.spinnaker.swabbie.test.TestResource
import org.junit.jupiter.api.Test

object LiteralExclusionPolicyTest {
  @Test
  fun `should exclude by name`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Literal.toString())
        .withAttributes(
          setOf(
            Attribute()
              .withKey("name")
              .withValue(
                listOf("test", "pattern:^ti")
              ),
            Attribute()
              .withKey("resourceId")
              .withValue(
                listOf("test", "pattern:^ti")
              )
          )
        )
    )

    listOf(
      TestResource("test", name = "test")
    ).filter {
      LiteralExclusionPolicy().apply(it, exclusions) == null
    }.let { filteredList ->
      filteredList.size shouldMatch equalTo(0)
    }
  }

  @Test
  fun `should exclude by arbitrary field`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Literal.toString())
        .withAttributes(
          setOf(
            Attribute()
              .withKey("resourceId")
              .withValue(
                listOf("test", "pattern:^.*?base_ami_id=", "pattern:(?!^.*?ancestor_id=)")
              )
          )
        )
    )

    listOf(
      TestResource(
        resourceId = "name=derivedsource, arch=x86_64, ancestor_name=xenialbase-x86_64-201806121200-ebs, " +
          "ancestor_id=ami-0c44ea1fc39e22303, ancestor_version=nflx-base-5.202.0-h721.5589019",
        name = "random 1"
      )
    ).filter {
      LiteralExclusionPolicy().apply(it, exclusions) == null
    }.let { filteredList ->
      filteredList.size shouldMatch equalTo(0)
    }
  }
}
