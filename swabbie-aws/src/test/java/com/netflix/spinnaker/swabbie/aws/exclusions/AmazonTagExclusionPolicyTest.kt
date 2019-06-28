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

package com.netflix.spinnaker.swabbie.aws.exclusions

import com.fasterxml.jackson.annotation.JsonTypeName
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

object AmazonTagExclusionPolicyTest {
  @Test
  fun `should exclude a resource with exclusion tag`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Tag.toString())
        .withAttributes(
          setOf(
            Attribute()
              .withKey("expiration_time")
              .withValue(
                listOf("never")
              )
          )
        )
    )

    val resources = listOf(
      AwsTestResource(id = "1")
        .withDetail(
          name = "tags",
          value = listOf(
            mapOf("expiration_time" to "never")
          )
        ),
      AwsTestResource(id = "2")
        .withDetail(
          name = "tags",
          value = listOf(
            mapOf("key" to "value")
          )
        ))

    resources.filter {
      AmazonTagExclusionPolicy().apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(1)
      filteredResources.first().resourceId shouldMatch equalTo("2")
    }
  }

  @Test
  fun `should exclude a resource based on temporal tags`() {
    val tenDays = 10L
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Tag.toString())
        .withAttributes(
          setOf(
            Attribute()
              .withKey("expiration_time")
              .withValue(
                listOf("pattern:^\\d+(d|m|y)\$")
              )
          )
        )
    )

    val resources = listOf(
      AwsTestResource(
        id = "1",
        creationDate = LocalDateTime.now().minusDays(tenDays).toString()
      ).withDetail(
        name = "tags",
        value = listOf(
          mapOf("expiration_time" to "${tenDays}d")
        )),
      AwsTestResource(
        id = "2",
        creationDate = LocalDateTime.now().minusDays(tenDays).toString()
      ).withDetail(
        name = "tags",
        value = listOf(
          mapOf("expiration_time" to "${tenDays - 1}d")
        )
      ))
    resources.filter {
      AmazonTagExclusionPolicy().apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(1)
      filteredResources.first().resourceId shouldMatch equalTo("2")
    }
  }
}

@JsonTypeName("R")
data class AwsTestResource(
  private val id: String,
  override val resourceId: String = id,
  override val name: String = "name",
  override val resourceType: String = "type",
  override val cloudProvider: String = "provider",
  private val creationDate: String = LocalDateTime.now().toString()
) : AmazonResource(creationDate)
