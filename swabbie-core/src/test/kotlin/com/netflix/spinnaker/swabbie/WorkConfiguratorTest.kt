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
import com.netflix.spinnaker.config.*
import com.netflix.spinnaker.swabbie.exclusions.AccountExclusionPolicy
import com.netflix.spinnaker.swabbie.model.Region
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

object WorkConfiguratorTest {
  private val accountProvider: AccountProvider = mock()

  @Test
  fun `should get accounts`() {
    val workConfigurator = WorkConfigurator(
      swabbieProperties = SwabbieProperties(),
      accountProvider = accountProvider,
      exclusionPolicies = listOf(mock())
    )

    whenever(accountProvider.getAccounts()) doReturn
      setOf(
        SpinnakerAccount(
          name = "test",
          accountId = "testId",
          type = "aws",
          edda = "",
          regions = emptyList(),
          eddaEnabled = false
        ),
        SpinnakerAccount(
          name = "testTitus",
          accountId = "prodId",
          type = "titus",
          edda = "",
          regions = emptyList(),
          eddaEnabled = false
        )
      )

    workConfigurator.getAccounts().size shouldMatch equalTo(2)
  }

  @Test
  fun `should generate a single work configuration with the proper granularity`() {
    val swabbieProperties = SwabbieProperties().apply {
      providers = listOf(
        CloudProviderConfiguration().apply {
          name = "aws"
          exclusions = mutableListOf()
          accounts = listOf("test")
          locations = listOf("us-east-1")
          resourceTypes = listOf(
            ResourceTypeConfiguration().apply {
              name = "loadBalancer"
              enabled = true
              dryRun = false
              retentionDays = 2
            }
          )
        }
      )
    }

    val workConfigurator = WorkConfigurator(
      swabbieProperties = swabbieProperties,
      accountProvider = accountProvider,
      exclusionPolicies = listOf(mock())
    )

    whenever(accountProvider.getAccounts()) doReturn
      setOf(
        SpinnakerAccount(
          name = "test",
          accountId = "testId",
          type = "aws",
          edda = "",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = true
        ),
        SpinnakerAccount(
          name = "testTitus",
          accountId = "prodId",
          type = "titus",
          edda = "",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = true
        )
      )

    workConfigurator.generateWorkConfigurations().let { workConfigurations ->
      workConfigurations.size shouldMatch equalTo(1)
      with(workConfigurations[0]) {
        assertEquals("aws:test:us-east-1:loadbalancer", namespace, "granularity")
        assertEquals(dryRun, true, "dryRun is on by default at the global level")
        resourceType shouldMatch equalTo("loadBalancer")
        location shouldMatch equalTo("us-east-1")
        with(account) {
          type shouldMatch equalTo("aws")
          name shouldMatch equalTo("test")
        }
      }
    }
  }

  @Test
  fun `should generate work configurations with the proper granularity`() {
    val swabbieProperties = SwabbieProperties().apply {
      dryRun = false
      providers = listOf(
        CloudProviderConfiguration().apply {
          name = "aws"
          exclusions = mutableListOf()
          accounts = listOf("test")
          locations = listOf("us-east-1")
          resourceTypes = listOf(
            ResourceTypeConfiguration().apply {
              name = "loadBalancer"
              enabled = true
              dryRun = false
            },
            ResourceTypeConfiguration().apply {
              name = "securityGroup"
              enabled = false
              dryRun = true
            },
            ResourceTypeConfiguration().apply {
              name = "serverGroup"
              enabled = true
              dryRun = true
            },
            ResourceTypeConfiguration().apply {
              name = "ami"
              enabled = true
              dryRun = false
              exclusions = mutableListOf(
                Exclusion()
                  .withType(ExclusionType.Account.toString())
                  .withAttributes(
                    listOf(
                      Attribute()
                        .withKey("name")
                        .withValue(
                          listOf("test")
                        )
                    )
                  )
              )
            }
          )
        }
      )
    }

    val workConfigurator = WorkConfigurator(
      swabbieProperties = swabbieProperties,
      accountProvider = accountProvider,
      exclusionPolicies = listOf(AccountExclusionPolicy())
    )

    whenever(accountProvider.getAccounts()) doReturn
      setOf(
        SpinnakerAccount(
          name = "test",
          accountId = "testId",
          type = "aws",
          edda = "",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false
        ),
        SpinnakerAccount(
          name = "testTitus",
          accountId = "prodId",
          type = "titus",
          edda = "",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false
        )
      )

    workConfigurator.generateWorkConfigurations().let { workConfigurations ->
      assertEquals(workConfigurations.size, 2,
        "excludes disabled securityGroup & ami because of the account exclusion by name")
      with(workConfigurations[0]) {
        assertEquals("aws:test:us-east-1:loadbalancer", namespace, "granularity")
        assertEquals(false, dryRun, "dryRun is false")
        resourceType shouldMatch equalTo("loadBalancer")
      }

      with(workConfigurations[1]) {
        assertEquals("aws:test:us-east-1:servergroup", namespace, "granularity")
        resourceType shouldMatch equalTo("serverGroup")
        assertEquals(true, dryRun, "dryRun is enabled at resource type level")
      }
    }
  }
}
