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

import com.netflix.spinnaker.swabbie.model.Resource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ResourceOwnerResolver(
  private val resourceOwnerStrategies: List<ResourceOwnerResolutionStrategy>
): OwnerResolver {
  @Value("\${swabbie.notify.fallbackEmail:cloudmonkeyalerts@netflix.com}")
  private lateinit var fallbackEmail: String
  override fun resolve(resource: Resource): String? {
    try {
      log.info("Looking up resource owner for {}", resource)
      resourceOwnerStrategies.mapNotNull {
        it.resolve(resource)
      }.let { owners ->
        return if (!owners.isEmpty()) {
          owners.first()
        } else {
          fallbackEmail
        }
      }
    } catch (e: Exception) {
      return fallbackEmail
    }
  }

  private val log: Logger = LoggerFactory.getLogger(javaClass)
}

interface OwnerResolver {
  fun resolve(resource: Resource): String?
}

interface ResourceOwnerResolutionStrategy {
  fun resolve(resource: Resource): String?
}

@Component
class TagOwnerResolutionStrategy: ResourceOwnerResolutionStrategy {
  override fun resolve(resource: Resource): String? {
    if ("tags" in resource.details) {
      return (resource.details["tags"] as Map<*, *>)["owner"] as? String
    }

    return null
  }
}
