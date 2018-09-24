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

import com.netflix.spinnaker.config.*
import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.events.OptOutResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AccountExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/resources")
class ResourceController(
  private val resourceStateRepository: ResourceStateRepository,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val workConfigurations: List<WorkConfiguration>,
  private val accountProvider: AccountProvider,
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

  /**
   * Runs resource through marking logic, calculating if it is a candidate for deletion.
   * Note: this api is quite slow, because does the actual recalculation for a resource.
   */
  @RequestMapping(value = ["/check/{namespace}/{resourceId}"], method = [RequestMethod.GET])
  fun checkResource(
    @PathVariable namespace: String,
    @PathVariable resourceId: String
  ): ResourceEvauation {
    val workConfiguration = getWorkConfiguration(namespaceParser(namespace))

    val handler = resourceTypeHandlers.find { handler ->
      handler.handles(workConfiguration)
    } ?: throw NotFoundException("No handlers for $namespace")

    return handler.evaluateCandidate(resourceId, resourceId, workConfiguration)
  }

  internal fun getWorkConfiguration(
    namespace: Namespace
  ): WorkConfiguration {
    return WorkConfigurator(
      swabbieProperties = generateSwabbieProperties(namespace),
      accountProvider = accountProvider,
      exclusionPolicies = listOf(AccountExclusionPolicy()),
      exclusionsSuppliers = Optional.empty()
    ).generateWorkConfigurations()[0]
  }

  internal fun generateSwabbieProperties(
    namespace: Namespace
  ): SwabbieProperties {
    val exclusionList = mutableListOf<Exclusion>()
    return SwabbieProperties().apply {
      dryRun = true
      providers = listOf(
        CloudProviderConfiguration().apply {
          name = namespace.cloudProvider
          exclusions = mutableListOf()
          accounts = listOf(namespace.accountId)
          locations = listOf(namespace.region)
          resourceTypes = listOf(
            ResourceTypeConfiguration().apply {
              name = namespace.resourceType
              enabled = true
              dryRun = true
              exclusions = exclusionList
            }
          )
        }
      )
    }
  }

  private fun namespaceParser(namespace: String): Namespace {
    namespace.split(":").let {
      return Namespace(it[0], it[1], it[2], it[3])
    }
  }
}

data class Namespace(
  val cloudProvider: String,
  val accountId: String,
  val region: String,
  val resourceType: String
) {
  override fun toString(): String {
    return "$cloudProvider:$accountId:$region:$resourceType"
  }
}
