/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.test

import com.netflix.spinnaker.config.BasicRule
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.swabbie.model.EmptyNotificationConfiguration
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.WorkConfiguration

class TestWorkConfigurationFactory(
  private val exclusions: List<Exclusion> = emptyList(),
  private val dryRun: Boolean = false,
  private val itemsProcessedBatchSize: Int = 1,
  private val maxItemsProcessedPerCycle: Int = 10,
  private val retention: Int = 14,
  private val notificationConfiguration: NotificationConfiguration = EmptyNotificationConfiguration(),
  private val rule: BasicRule? = null,
  private val cloudProvider: String = "testCloudProvider",
  private val resourceType: String = "testResourceType",
  private val location: String = "us-east-1"
) {
  fun get(): WorkConfiguration {
    return WorkConfiguration(
      namespace = "$cloudProvider:$resourceType:$location:$resourceType",
      account = SpinnakerAccount(
        name = "test",
        accountId = "id",
        type = "type",
        edda = "",
        regions = emptyList(),
        eddaEnabled = false,
        environment = "test"
      ),
      location = "us-east-1",
      cloudProvider = cloudProvider,
      resourceType = resourceType,
      retention = retention,
      exclusions = exclusions.toSet(),
      dryRun = dryRun,
      maxAge = 1,
      itemsProcessedBatchSize = itemsProcessedBatchSize,
      maxItemsProcessedPerCycle = maxItemsProcessedPerCycle,
      notificationConfiguration = notificationConfiguration,
      rule = rule
    )
  }
}
