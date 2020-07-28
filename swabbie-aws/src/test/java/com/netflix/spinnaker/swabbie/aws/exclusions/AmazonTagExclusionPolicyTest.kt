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
import com.netflix.spinnaker.kork.test.time.MutableClock
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

object AmazonTagExclusionPolicyTest {
  private val clock = MutableClock()

  @BeforeEach
  fun setup() {
    clock.instant(Instant.now())
  }

  private val subject = AmazonTagExclusionPolicy(clock)
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
              ),
            Attribute()
              .withKey("excludeMe")
              .withValue(
                listOf(true)
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
        ),
      AwsTestResource(id = "3")
        .withDetail(
          name = "tags",
          value = listOf(
            mapOf("excludeMe" to true)
          )
        )
    )

    resources.filter {
      subject.apply(it, exclusions) == null
    }.let { filteredResources ->
      filteredResources.size shouldMatch equalTo(1)
      filteredResources.first().resourceId shouldMatch equalTo("2")
    }
  }

  @Test
  fun `should exclude a resource based on temporal tags`() {
    val now = LocalDateTime.now(clock)
    val resources = listOf(
      AwsTestResource(
        id = "1",
        creationDate = now.toString()
      ).withDetail(
        name = "tags",
        value = listOf(
          mapOf("expiration_time" to "10d")
        )
      ),
      AwsTestResource(
        id = "2",
        creationDate = now.toString()
      ).withDetail(
        name = "tags",
        value = listOf(
          mapOf("expiration_time" to "9d")
        )
      )
    )

    clock.incrementBy(Duration.ofDays(10))

    resources.filter {
      subject.apply(it, emptyList()) == null
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
