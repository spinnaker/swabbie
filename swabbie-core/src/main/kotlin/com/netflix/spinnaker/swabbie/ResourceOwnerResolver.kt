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
import java.util.regex.Pattern
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Looks at all available resolution strategies for finding an owner for a resource,
 * and returns all owners.
 */
@Component
open class ResourceOwnerResolver<in T : Resource>(
  private val registry: Registry,
  private val resourceOwnerResolutionStrategies: List<ResourceOwnerResolutionStrategy<T>>
) : OwnerResolver<T> {
  private val resourceOwnerId = registry.createId("swabbie.resources.owner")

  override fun resolve(resource: T): String? {
    try {
      val otherOwners = mutableSetOf<String>()
      val primaryOwners = mutableSetOf<String>()

      resourceOwnerResolutionStrategies.forEach { strategy ->
        val owner = strategy.resolve(resource)
        owner?.let {
          if (strategy.primaryFor().contains(resource.resourceType)) {
            primaryOwners.add(owner)
          } else {
            otherOwners.add(owner)
          }
        }
      }

      val validEmails = findValidEmails(if (primaryOwners.isNotEmpty()) primaryOwners else otherOwners)

      return if (validEmails.isNotEmpty()) {
        registry.counter(
          resourceOwnerId.withTags(
            "result", "found",
            "resourceType", resource.resourceType
          )
        ).increment()
        validEmails.joinToString(",")
      } else {
        registry.counter(
          resourceOwnerId.withTags(
            "result", "notFound",
            "resourceType", resource.resourceType
          )
        ).increment()
        null
      }
    } catch (e: Exception) {
      log.warn("Failed to find owner for {}.", resource, e)
      registry.counter(resourceOwnerId.withTags("result", "failed")).increment()
      return null
    }
  }

  private fun findValidEmails(emails: Set<String>): List<String> {
    return emails.filter {
      Pattern.compile(
        "^(([\\w-]+\\.)+[\\w-]+|([a-zA-Z]|[\\w-]{2,}))@" +
          "((([0-1]?[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\.([0-1]?" +
          "[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\." +
          "([0-1]?[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\.([0-1]?" +
          "[0-9]{1,2}|25[0-5]|2[0-4][0-9]))|" +
          "([a-zA-Z]+[\\w-]+\\.)+[a-zA-Z]{2,4})$"
      ).matcher(it).matches()
    }
  }

  private val log: Logger = LoggerFactory.getLogger(javaClass)
}

interface OwnerResolver<in T : Resource> {
  fun resolve(resource: T): String?
}

/**
 * Defines a specific way to find an owner for a resource,
 * for example by a tag, from an application, or from an external system
 */
interface ResourceOwnerResolutionStrategy<in T : Resource> {
  fun resolve(resource: T): String?

  /**
   * The resource types for which this resolution strategy knows the primary owner
   */
  fun primaryFor(): Set<String>
}
