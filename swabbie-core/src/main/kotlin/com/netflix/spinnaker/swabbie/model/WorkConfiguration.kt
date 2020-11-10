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
import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.config.ResourceTypeConfiguration
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.events.Action
import org.slf4j.LoggerFactory

/**
 * @param retention How many days swabbie will wait until starting the deletion process
 * @param maxAge resources newer than the maxAge in days will be excluded
 */
data class WorkConfiguration(
  val namespace: String,
  val account: Account,
  val location: String,
  val cloudProvider: String,
  val resourceType: String,
  val retention: Int,
  val exclusions: Set<Exclusion>,
  val dryRun: Boolean = true,
  val entityTaggingEnabled: Boolean = false,
  val notificationConfiguration: NotificationConfiguration = EmptyNotificationConfiguration(),
  val maxAge: Int = 14,
  val maxItemsProcessedPerCycle: Int = 10,
  val itemsProcessedBatchSize: Int = 5,
  val deleteSpreadMs: Long = 0L,
  val enabledActions: List<Action> = listOf(Action.MARK, Action.NOTIFY, Action.DELETE),
  val enabledRules: Set<ResourceTypeConfiguration.RuleConfiguration> = setOf()
) {
  private val log = LoggerFactory.getLogger(javaClass)

  fun toLog(): String = "$namespace/${account.accountId}/$location/$cloudProvider/$resourceType"
  fun toWorkItems(): List<WorkItem> {
    log.info("Actions $enabledActions enabled for ${toLog()}")
    return enabledActions.map {
      WorkItem(id = "${it.name}:$namespace".toLowerCase(), workConfiguration = this, action = it)
    }
  }

  override fun equals(other: Any?): Boolean {
    return (other != null && other is WorkConfiguration && other.namespace == namespace)
  }

  override fun hashCode(): Int {
    return namespace.hashCode()
  }

  fun getMaxItemsProcessedPerCycle(dynamicConfigService: DynamicConfigService): Int {
    val key = "$namespace.max-items-processed-per-cycle"
    return dynamicConfigService.getConfig(Int::class.java, key, maxItemsProcessedPerCycle)
  }

  fun getDeleteSpreadMs(dynamicConfigService: DynamicConfigService): Long {
    val key = "$namespace.delete-spread-ms"
    return dynamicConfigService.getConfig(Long::class.java, key, deleteSpreadMs)
  }
}

/**
 * A unit of work associated with a [WorkConfiguration]
 */
data class WorkItem(
  val id: String,
  val workConfiguration: WorkConfiguration,
  val action: Action
)

class EmptyNotificationConfiguration : NotificationConfiguration(
  enabled = false,
  types = mutableListOf(),
  optOutBaseUrl = ""
)
