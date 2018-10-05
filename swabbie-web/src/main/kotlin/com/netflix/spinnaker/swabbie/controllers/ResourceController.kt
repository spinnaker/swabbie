/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.controllers

import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.OptOutResourceEvent
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/resources")
class ResourceController(
  private val resourceStateRepository: ResourceStateRepository,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val workConfigurations: List<WorkConfiguration>,
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>
) {

  private val log = LoggerFactory.getLogger(javaClass)

  @RequestMapping(value = ["/marked"], method = [RequestMethod.GET])
  fun markedResources(
    @RequestParam(required = false, defaultValue = "false") expand: Boolean
  ): List<MarkedResourceInterface> {
    return resourceTrackingRepository.getMarkedResources().let { markedResources ->
      if (expand) markedResources else markedResources.map { it.slim() }
    }
  }

  @RequestMapping(value = ["/marked/{namespace}"], method = [RequestMethod.GET])
  fun markedResource(
    @PathVariable namespace: String,
    @RequestParam(required = false, defaultValue = "false") expand: Boolean
  ): List<MarkedResourceInterface> {
    return resourceTrackingRepository.getMarkedResources()
      .filter { it.namespace == namespace }
      .let { markedResources ->
        if (expand) markedResources else markedResources.map { it.slim() }
      }
  }

  @RequestMapping(value = ["/marked/{namespace}/{resourceId}"], method = [RequestMethod.GET])
  fun markedResource(
    @PathVariable namespace: String,
    @PathVariable resourceId: String
  ): MarkedResource {
    return resourceTrackingRepository.find(resourceId, namespace)
      ?: throw NotFoundException("Resource $namespace/$resourceId not found")
  }

  @RequestMapping(value = ["/canDelete"], method = [RequestMethod.GET])
  fun markedResourcesReadyForDeletion(): List<MarkedResource> =
    resourceTrackingRepository.getMarkedResourcesToDelete()

  @RequestMapping(value = ["/states/{status}"], method = [RequestMethod.GET])
  fun getResourcesByState(
    @PathVariable status: String
  ): List<ResourceState> {
    val parsedStatus = status.toUpperCase()
    if (!Action.values().map { it.toString() }.contains(parsedStatus) && parsedStatus != "FAILED"){
      throw IllegalArgumentException(
        "Status $status is not a valid status. Options are: ${Action.values().map { it.toString() }} or FAILED"
      )
    }
    return resourceStateRepository.getByStatus(status)
  }


  @RequestMapping(value = ["/canSoftDelete"], method = [RequestMethod.GET])
  fun markedResourcesReadyForSoftDeletion(): List<MarkedResource> =
    resourceTrackingRepository.getMarkedResourcesToSoftDelete()

  @RequestMapping(value = ["/states"], method = [RequestMethod.GET])
  fun states(
    @RequestParam(required = false) start: Int?,
    @RequestParam(required = false) limit: Int?
  ): List<ResourceState> {
    //todo eb: sort status entries by time for all resources
    return resourceStateRepository.getAll().apply {
      this.subList(start ?: 0, Math.min(this.size, limit ?: Int.MAX_VALUE ))
    }
  }

  @RequestMapping(value = ["/state"], method = [RequestMethod.GET])
  fun state(
    @RequestParam provider: String,
    @RequestParam account: String,
    @RequestParam location: String,
    @RequestParam resourceId: String,
    @RequestParam resourceType: String
  ): ResourceState =
    "$provider:$account:$location:$resourceType".toLowerCase()
      .let {
        return resourceStateRepository.get(resourceId, it) ?: throw NotFoundException()
      }

  @RequestMapping(value = ["/state/{resourceId}"], method = [RequestMethod.GET])
  fun state(
    @PathVariable resourceId: String
  ): List<ResourceState> =
    resourceStateRepository.getAll().filter { it.markedResource.resourceId == resourceId }


  @RequestMapping(value = ["/state/{namespace}/{resourceId}/optOut"], method = [RequestMethod.PUT])
  fun optOut(
    @PathVariable resourceId: String,
    @PathVariable namespace: String
  ) : ResourceState {
    resourceTrackingRepository.find(resourceId, namespace)?.let { markedResource ->
      resourceTrackingRepository.remove(markedResource)
      workConfigurations.find {
        it.namespace.equals(namespace, true)
      }?.also { configuration ->
        applicationEventPublisher.publishEvent(OptOutResourceEvent(markedResource, configuration))
      }
    }

    return resourceStateRepository.get(resourceId, namespace) ?: throw NotFoundException()
  }

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
   * Soft deletes or deletes a resource
   */
  @RequestMapping(value = ["/state/{namespace}/{resourceId}"], method = [RequestMethod.DELETE])
  fun softDelete(
    @PathVariable resourceId: String,
    @PathVariable namespace: String,
    @RequestParam(required = true) soft: Boolean
  ) {
    val workConfiguration = findWorkConfiguration(SwabbieNamespace.namespaceParser(namespace))
    val handler = resourceTypeHandlers.find { handler ->
      handler.handles(workConfiguration)
    } ?: throw NotFoundException("No handlers for $namespace")

    if (soft) {
      handler.softDeleteResource(resourceId, workConfiguration)
    } else {
      handler.deleteResource(resourceId, workConfiguration)
    }
  }

  /**
   * Restores a resource that has been soft-deleted
   */
  @RequestMapping(value = ["/state/{namespace}/{resourceId}/restore"], method = [RequestMethod.PUT])
  fun restore(
    @PathVariable resourceId: String,
    @PathVariable namespace: String
  ) {
    val workConfiguration = findWorkConfiguration(SwabbieNamespace.namespaceParser(namespace))
    val handler = resourceTypeHandlers.find { handler ->
      handler.handles(workConfiguration)
    } ?: throw NotFoundException("No handlers for $namespace")

    handler.restoreResource(resourceId, workConfiguration)
  }

  /**
   * Runs resource through marking logic, calculating if it is a candidate for deletion.
   * Note: this api is quite slow, because does the actual recalculation for a resource.
   */
  @RequestMapping(value = ["/check/{namespace}/{resourceId}"], method = [RequestMethod.GET])
  fun checkResource(
    @PathVariable namespace: String,
    @PathVariable resourceId: String
  ): ResourceEvaluation {
    val workConfiguration = findWorkConfiguration(SwabbieNamespace.namespaceParser(namespace))
    val newWorkConfiguration = workConfiguration.copy(dryRun = true)

    val handler = resourceTypeHandlers.find { handler ->
      handler.handles(newWorkConfiguration)
    } ?: throw NotFoundException("No handlers for $namespace")

    return handler.evaluateCandidate(resourceId, resourceId, newWorkConfiguration)
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
