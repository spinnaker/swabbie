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

import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.ResourceOwnerResolutionStrategy
import com.netflix.spinnaker.swabbie.krieger.KriegerService
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.Resource
import org.springframework.stereotype.Component

@Component
class ApplicationResourceOwnerResolutionStrategy(
  private val front50ApplicationCache: InMemoryCache<Application>,
  private val kriegerApplicationCache: InMemoryCache<Application>
) : ResourceOwnerResolutionStrategy<Resource> {
  override fun resolve(resource: Resource): String? {
    // krieger returns applications with relationships to other resources: images, launch configs...
    // Using a mapping to swabbie types to adapt to general use case. Krieger not required for this resolution to work.

    // 1. Find applications from Krieger containing a reference to this resource.
    val applicationToOwnersPairs = mutableSetOf<Pair<String?, String?>>()
    if (resource.resourceType in KriegerService.kriegerFieldsToSwabbieTypes) {
      KriegerService.kriegerFieldsToSwabbieTypes[resource.resourceType]!!.let { kriegerFieldAndTypePair ->
        kriegerApplicationCache.get().filter { application ->
          application.details[kriegerFieldAndTypePair.first] != null
        }.map { application ->

          // 2. Extract owner information for every matched application. Handle primitive & non-primitive relationships
          getKriegerPotentialOwnersOrNull(application)?.let { potentialOwners ->
            when {
              kriegerFieldAndTypePair.second == Collection::class.java ->
                (application.details[kriegerFieldAndTypePair.first]!! as Collection<*>).let { interestingObjects ->
                  when {
                    resource.resourceType in KriegerService.swabbieTypesTokriegerIdentifiableFields -> {
                      val interestingId = KriegerService
                        .swabbieTypesTokriegerIdentifiableFields[resource.resourceType]!!

                      interestingObjects.any {
                        it is Map<*, *> && resource.resourceId == it[interestingId]
                      }.let { isInterestingApplication ->
                        if (isInterestingApplication) {
                          log.debug("resolved {} owners from Krieger application {}", resource.resourceId, application)
                          applicationToOwnersPairs.add(Pair(application.name, potentialOwners))
                        }
                      }
                    }

                    interestingObjects.contains(resource.resourceId) -> {
                      log.debug("resolved {} owners from Krieger application {}", resource.resourceId, application)
                      applicationToOwnersPairs.add(Pair(application.name, potentialOwners))
                    }
                    else -> log.debug("No matched owners found in Krieger for {}", resource)
                  }
                }

              kriegerFieldAndTypePair.second == String::class.java -> {
                log.debug("resolved {} owners from Krieger application {}", resource.resourceId, application)
                applicationToOwnersPairs.add(Pair(application.name, potentialOwners))
              }
              else -> {
                log.debug("No matched owners found in Krieger for {} using type {}",
                  resource, kriegerFieldAndTypePair)
              }
            }
          }

          applicationToOwnersPairs
        }
      }
    }

    // 3. Derive object's moniker to find matching front50 apps.
    FriggaReflectiveNamer().deriveMoniker(resource).app?.let { derivedApp ->
      front50ApplicationCache.get().filter { application ->
        application.monikerMatched(applicationToOwnersPairs, derivedApp)
      }.map { application ->
        log.debug("resolved {} owners from front50 application {}", resource.resourceId, application)
        applicationToOwnersPairs.add(Pair(application.name, application.email))
      }
    }

    // 4. Massage result by filtering out misses.
    applicationToOwnersPairs.removeIf { it.first == null || it.second == null }
    if (applicationToOwnersPairs.isEmpty()) {
      log.debug("No matched owners with strategy {} for resoure {}", javaClass.simpleName, resource)
      return null
    }

    // Join matches with ","
    return applicationToOwnersPairs.map {
      it.second
    }.joinToString(",")
  }

  /**
   * Matches an app by name. Skip memoized items
   */
  private fun Application.monikerMatched(
    computedMatches: MutableSet<Pair<String?, String?>>,
    moniker: String
  ): Boolean {
    return !computedMatches.any {
      it.first.equals(name, true)
    } && name.equals(moniker, ignoreCase = true)
  }

  /**
   * Returns a list of comma separated owners if found or null
   */
  private fun getKriegerPotentialOwnersOrNull(application: Application): String? {
    mutableSetOf(
      application.email,
      (application.details["declaredOwner"] as? Map<*, *>)?.get("email") as? String,
      (application.details["owners"] as? List<*>)?.joinToString(","),
      (application.details["contacts"] as? List<*>)?.joinToString(",")
    ).apply {
      remove(null)
    }.let {
      return if (it.isNotEmpty()) {
        it.joinToString(",")
      } else {
        null
      }
    }
  }
}
