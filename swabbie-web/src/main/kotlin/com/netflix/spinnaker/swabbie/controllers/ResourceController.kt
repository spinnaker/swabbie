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
import com.netflix.spinnaker.swabbie.ResourceStateRepository
import com.netflix.spinnaker.swabbie.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.events.OptOutResourceEvent
import com.netflix.spinnaker.swabbie.model.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/resources")
class ResourceController(
  private val resourceStateRepository: ResourceStateRepository,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val workConfigurations: List<WorkConfiguration>
  ) {

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
  fun markedResourcesReadyForDeletion(): List<MarkedResource> = resourceTrackingRepository.getMarkedResourcesToDelete()

  @RequestMapping(value = ["/states"], method = [RequestMethod.GET])
  fun states(
    @RequestParam(required = false) start: Int?,
    @RequestParam(required = false) limit: Int?
  ): List<ResourceState> {
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
}




