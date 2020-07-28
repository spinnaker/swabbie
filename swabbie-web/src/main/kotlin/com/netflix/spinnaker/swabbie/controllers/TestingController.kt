/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.controllers

import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.OnDemandMarkData
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.SwabbieNamespace
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationSender
import com.netflix.spinnaker.swabbie.test.TestResource
import java.time.Clock
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/**
 * This controller is for testing resources by on demand marking and deleting them.
 * In order to take any action on a resource you must also enable the [AlwaysCleanRule], so that your config
 * looks something like:
 * swabbie:
 *   testing:
 *     enabled: true
 *     alwaysCleanRuleConfig:
 *       enabled: true
 *       resourceIds:
 *         - resourceId1, like ami-blahblah
 */
@RestController
@ConditionalOnExpression("\${swabbie.testing.enabled:false}")
@RequestMapping("/testing/resources")
class TestingController(
  private val workConfigurations: List<WorkConfiguration>,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>,
  private val notificationSender: NotificationSender,
  private val clock: Clock
) {

  /**
   * FOR TESTING
   * Marks an image for deletion with the given soft delete and deletion times.
   * Body contains information needed for marking
   */
  @RequestMapping(value = ["/state/{namespace}/{resourceId}/mark"], method = [RequestMethod.PUT])
  fun onDemandMark(
    @PathVariable resourceId: String,
    @PathVariable namespace: String,
    @RequestBody markInformation: OnDemandMarkData
  ) {
    val workConfiguration = findWorkConfiguration(SwabbieNamespace.namespaceParser(namespace))
    val handler = resourceTypeHandlers.find { handler ->
      handler.handles(workConfiguration)
    } ?: throw NotFoundException("No handlers for $namespace")

    handler.markResource(resourceId, markInformation, workConfiguration)
  }

  /**
   * FOR TESTING
   * Deletes a resource
   */
  @RequestMapping(value = ["/state/{namespace}/{resourceId}"], method = [RequestMethod.DELETE])
  fun delete(
    @PathVariable resourceId: String,
    @PathVariable namespace: String
  ) {
    val workConfiguration = findWorkConfiguration(SwabbieNamespace.namespaceParser(namespace))
    val handler = resourceTypeHandlers.find { handler ->
      handler.handles(workConfiguration)
    } ?: throw NotFoundException("No handlers for $namespace")

    handler.deleteResource(resourceId, workConfiguration)
  }

  /**
   * FOR TESTING
   * Trigger's email notification for the given single resource
   */
  @RequestMapping(value = ["/notify/{namespace}/{resourceId}"], method = [RequestMethod.POST])
  fun notify(
    @PathVariable resourceId: String,
    @PathVariable namespace: String,
    @RequestBody resource: OnDemandMarkData
  ) {
    val workConfiguration = findWorkConfiguration(SwabbieNamespace.namespaceParser(namespace))
    val markedResource = createMarkedResource(
      workConfiguration,
      resource.resourceId!!,
      resource.resourceOwner,
      resource.projectedDeletionStamp,
      resource.markTs
    )
    val notificationResourceData = NotificationSender.NotificationResourceData(
      resourceType = workConfiguration.resourceType,
      resourceUrl = markedResource.resource.resourceUrl(workConfiguration),
      account = workConfiguration.account.name!!,
      location = workConfiguration.location,
      optOutUrl = markedResource.optOutUrl(workConfiguration),
      resource = markedResource,
      deletionDate = markedResource.deletionDate(clock).toString()
    )
    notificationSender.notifyUser(
      markedResource.resourceOwner,
      markedResource.resourceType,
      listOf(notificationResourceData),
      workConfiguration.notificationConfiguration
    )
  }

  private fun findWorkConfiguration(namespace: SwabbieNamespace): WorkConfiguration {
    return workConfigurations.find { workConfiguration ->
      workConfiguration.account.name == namespace.accountName &&
        workConfiguration.cloudProvider == namespace.cloudProvider &&
        workConfiguration.resourceType == namespace.resourceType &&
        workConfiguration.location == namespace.region
    } ?: throw NotFoundException("No configuration found for $namespace")
  }

  // Helper method to create a marked resource
  private fun createMarkedResource(
    workConfiguration: WorkConfiguration,
    id: String,
    owner: String,
    projectedDeletionStamp: Long,
    markTs: Long?
  ): MarkedResource {
    return MarkedResource(
      resource = TestResource(
        resourceId = id,
        name = id,
        resourceType = workConfiguration.resourceType,
        cloudProvider = workConfiguration.cloudProvider
      ),
      summaries = listOf(Summary("invalid", "rule $id")),
      namespace = workConfiguration.namespace,
      projectedDeletionStamp = projectedDeletionStamp,
      markTs = markTs,
      resourceOwner = owner
    )
  }
}
