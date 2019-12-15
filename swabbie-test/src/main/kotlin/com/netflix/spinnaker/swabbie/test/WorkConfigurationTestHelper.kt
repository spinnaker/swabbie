/*
 *
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
 *
 */

package com.netflix.spinnaker.swabbie.test

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.swabbie.model.EmptyNotificationConfiguration
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.WorkConfiguration

object WorkConfigurationTestHelper {
  fun generateWorkConfiguration(
    exclusions: List<Exclusion> = emptyList(),
    dryRun: Boolean = false,
    itemsProcessedBatchSize: Int = 1,
    maxItemsProcessedPerCycle: Int = 10,
    retention: Int = 14,
    notificationConfiguration: NotificationConfiguration = EmptyNotificationConfiguration(),
    resourceType: String = TEST_RESOURCE_TYPE,
    cloudProvider: String = TEST_RESOURCE_PROVIDER_TYPE,
    namespace: String = "$cloudProvider:test:us-east-1:$resourceType"
  ): WorkConfiguration = WorkConfiguration(
    namespace = namespace,
    account = testAccount,
    location = "us-east-1",
    cloudProvider = cloudProvider,
    resourceType = resourceType,
    retention = retention,
    exclusions = exclusions.toSet(),
    dryRun = dryRun,
    maxAge = 0,
    itemsProcessedBatchSize = itemsProcessedBatchSize,
    maxItemsProcessedPerCycle = maxItemsProcessedPerCycle,
    notificationConfiguration = notificationConfiguration
  )

  private val testAccount = SpinnakerAccount(
    name = "test",
    accountId = "id",
    type = "type",
    edda = "",
    regions = emptyList(),
    eddaEnabled = false,
    environment = "test"
  )
}
