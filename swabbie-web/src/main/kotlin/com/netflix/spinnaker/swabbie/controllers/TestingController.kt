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
import com.netflix.spinnaker.swabbie.model.OnDemandMarkData
import com.netflix.spinnaker.swabbie.model.SwabbieNamespace
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.bind.annotation.*

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
class TestingController (
  private val workConfigurations: List<WorkConfiguration>,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>
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

  private fun findWorkConfiguration(namespace: SwabbieNamespace): WorkConfiguration {
    return workConfigurations.find { workConfiguration ->
      workConfiguration.account.name == namespace.accountName
        && workConfiguration.cloudProvider == namespace.cloudProvider
        && workConfiguration.resourceType == namespace.resourceType
        && workConfiguration.location == namespace.region
    } ?: throw NotFoundException("No configuration found for $namespace")
  }
}
