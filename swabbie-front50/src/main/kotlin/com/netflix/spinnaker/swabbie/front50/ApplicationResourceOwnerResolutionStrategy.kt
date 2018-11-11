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

package com.netflix.spinnaker.swabbie.front50

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.ResourceOwnerResolutionStrategy
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.Resource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ApplicationResourceOwnerResolutionStrategy(
  private val front50ApplicationCache: InMemoryCache<Application>,
  private val registry: Registry
) : ResourceOwnerResolutionStrategy<Resource> {
  private val log: Logger = LoggerFactory.getLogger(this.javaClass)
  private val resourceOwnerId = registry.createId("swabbie.resources.owner")

  override fun primaryFor(): Set<String> = emptySet()

  override fun resolve(resource: Resource): String? {
    val applicationToOwnersPairs = mutableSetOf<Pair<String?, String?>>()

    FriggaReflectiveNamer().deriveMoniker(resource).app?.let { derivedApp ->
      applicationToOwnersPairs.addAll(getMatchingFront50Applications(derivedApp))
    }

    applicationToOwnersPairs.removeIf { it.first == null || it.second == null }

    if (applicationToOwnersPairs.isEmpty()) {
      registry.counter(
        resourceOwnerId.withTags(
          "strategy", javaClass.simpleName,
          "resourceType", resource.resourceType,
          "result", "notFound"
        )
      ).increment()

      return null
    }

    registry.counter(
      resourceOwnerId.withTags(
        "strategy", javaClass.simpleName,
        "resourceType", resource.resourceType,
        "result", "found"
      )
    ).increment()

    return applicationToOwnersPairs.map {
      it.second
    }.joinToString(",")
  }

  private fun getMatchingFront50Applications(derivedAppName: String): Set<Pair<String,String?>> {
    return front50ApplicationCache.get().filter { application ->
      application.name.equals(derivedAppName, ignoreCase = true)
    }.map { application ->
      Pair(application.name, application.email)
    }.toSet()
  }
}
