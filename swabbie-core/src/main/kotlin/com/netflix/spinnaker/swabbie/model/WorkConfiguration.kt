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

package com.netflix.spinnaker.swabbie.model

import com.netflix.spinnaker.config.Exclusion

data class WorkConfiguration(
  val namespace: String,
  val account: Account,
  val location: String,
  val cloudProvider: String,
  val resourceType: String,
  val retentionDays: Int,
  val exclusions: List<Exclusion>,
  val dryRun: Boolean = true,
  val notificationConfiguration: NotificationConfiguration? = EmptyNotificationConfiguration()
)

open class NotificationConfiguration(
  val notifyOwner: Boolean,
  val optOutUrl: String,
  val resourcesPerNotification: Int,
  val spinnakerResourceUrl: String
)

class EmptyNotificationConfiguration : NotificationConfiguration(
  false,
  "",
  0,
  ""
)
