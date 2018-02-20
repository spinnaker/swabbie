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

package com.netflix.spinnaker.swabbie.configuration

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.natpryce.hamkrest.should.shouldNotMatch
import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.exclusions.AccountNameExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.AccountTypeExclusionPolicy
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.junit.jupiter.api.Test


object AccountExclusionPolicyTest {

  @Test
  fun `should exclude an account by name`() {
    val accountName = "test"
    val configuration = WorkConfiguration(
      namespace = "aws:test:us-east-1:securityGroup",
      account = SpinnakerAccount(name = accountName, accountId = "id", type = "aws"),
      location = "us-east-1",
      cloudProvider = "aws",
      resourceType = "securityGroup",
      retentionDays = 14,
      dryRun = false,
      exclusions = listOf(
        Exclusion()
          .withType(ExclusionType.AccountName.toString())
          .withAttributes(
            listOf(
              Attribute()
                .withKey("account")
                .withValue(listOf(accountName))
            )
          )
      )
    )

    AccountNameExclusionPolicy()
      .apply(configuration, configuration.exclusions) shouldMatch equalTo(true)

    AccountNameExclusionPolicy()
      .apply(
        configuration.copy(account = SpinnakerAccount(name = "other", accountId = "id", type = "aws")),
        configuration.exclusions
      ) shouldNotMatch  equalTo(true)
  }

  @Test
  fun `should exclude an account by type`() {
    val accountType = "aws"
    val configuration = WorkConfiguration(
      namespace = "aws:test:us-east-1:securityGroup",
      account = SpinnakerAccount(name = "test", accountId = "id", type = accountType),
      location = "us-east-1",
      cloudProvider = "aws",
      resourceType = "securityGroup",
      retentionDays = 14,
      dryRun = false,
      exclusions = listOf(
        Exclusion()
          .withType(ExclusionType.AccountType.toString())
          .withAttributes(
            listOf(
              Attribute()
                .withKey("account")
                .withValue(listOf(accountType))
            )
          )
      )
    )

    AccountTypeExclusionPolicy()
      .apply(configuration, configuration.exclusions) shouldMatch equalTo(true)

    AccountTypeExclusionPolicy()
      .apply(
        configuration.copy(account = SpinnakerAccount(name = "test", accountId = "id", type = "other")),
        configuration.exclusions
      ) shouldNotMatch  equalTo(true)
  }

}
