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
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import org.junit.jupiter.api.Test

object AccountExclusionPolicyTest {
  @Test
  fun `should exclude accounts by name`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Account.toString())
        .withAttributes(
          setOf(
            Attribute()
              .withKey("name")
              .withValue(
                listOf("test", "pattern:^ti")
              )
          )
        )
    )

    val accounts = listOf(
      SpinnakerAccount(
        name = "test",
        accountId = "1",
        type = "aws",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      ),
      SpinnakerAccount(
        name = "testing",
        accountId = "2",
        type = "aws",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      ),
      SpinnakerAccount(
        name = "titustest",
        accountId = "2",
        type = "titus",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      )
    )

    accounts.filter {
      AccountExclusionPolicy().apply(it, exclusions) == null
    }.let { filteredAccounts ->
      filteredAccounts.size shouldMatch equalTo(1)
      filteredAccounts.first().name shouldMatch equalTo("testing")
    }
  }

  @Test
  fun `should exclude accounts by type`() {
    val exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Account.toString())
        .withAttributes(
          setOf(
            Attribute()
              .withKey("type")
              .withValue(
                listOf("titus", "pattern:^aw")
              )
          )
        )
    )

    val accounts = listOf(
      SpinnakerAccount(
        name = "test",
        accountId = "1",
        type = "aws",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      ),
      SpinnakerAccount(
        name = "testing",
        accountId = "2",
        type = "aws",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      ),
      SpinnakerAccount(
        name = "titustest",
        accountId = "3",
        type = "titus",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      ),
      SpinnakerAccount(
        name = "other",
        accountId = "4",
        type = "other",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      )
    )

    accounts.filter {
      AccountExclusionPolicy().apply(it, exclusions) == null
    }.let { filteredAccounts ->
      filteredAccounts.size shouldMatch equalTo(1)
      filteredAccounts.first().type shouldMatch equalTo("other")
    }
  }
}
