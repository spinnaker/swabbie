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

package com.netflix.spinnaker.swabbie

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.model.Resource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
open class ResourceOwnerResolver<in T : Resource>(
  private val registry: Registry,
  private val resourceOwnerResolutionStrategies: List<ResourceOwnerResolutionStrategy<T>>
) : OwnerResolver<T> {
  @Value("\${swabbie.notify.fallbackEmail:swabbie@netflix.com}")
  private lateinit var fallbackEmail: String

  private val resourceOwnerId = registry.createId("swabbie.resources.owner")
  override fun resolve(resource: T): String? {
    try {
      resourceOwnerResolutionStrategies.mapNotNull {
        it.resolve(resource)
      }.let { owners ->
        return if (!owners.isEmpty()) {
          registry.counter(resourceOwnerId.withTags("result", "found")).increment()
          owners.first()
        } else {
          registry.counter(resourceOwnerId.withTags("result", "notFound")).increment()
          fallbackEmail
        }
      }
    } catch (e: Exception) {
      log.info("Failed to find owner for {}. falling back on {}", resource, fallbackEmail, e)
      registry.counter(resourceOwnerId.withTags("result", "failed")).increment()
      return fallbackEmail
    }
  }

  private val log: Logger = LoggerFactory.getLogger(javaClass)
}

interface OwnerResolver<in T : Resource> {
  fun resolve(resource: T): String?
}

interface ResourceOwnerResolutionStrategy<in T : Resource> {
  fun resolve(resource: T): String?
}
