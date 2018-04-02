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

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.exclusions.Excludable
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.Resource
import org.springframework.stereotype.Component

@Component
class Front50ApplicationExclusionPolicy(
  private val front50ApplicationCache: InMemoryCache<Application>
) : ResourceExclusionPolicy {
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): Boolean {
    val derivedApp: String? = FriggaReflectiveNamer().deriveMoniker(excludable).app?.toLowerCase()
    if (excludable is Resource && derivedApp != null) {
      whitelist(exclusions, ExclusionType.Application).let { list ->
        if (!list.isEmpty()) {
          if (!list.contains(derivedApp) && list.find { derivedApp.matchPattern(it) } == null) {
            log.info("Skipping {} because {} not in provided application whitelist", excludable.name, derivedApp)
            return true
          }
        }
      }
    }

    keysAndValues(exclusions, ExclusionType.Application).let { map ->
      front50ApplicationCache.get().find { it.name.equals(derivedApp, ignoreCase = true) }?.let {
        if (map["name"] != null && map["name"]!!.contains(it.name)) {
          log.info("Skipping {} excluded by name", excludable.name)
          return true
        }

        if (map["email"] != null && map["email"]!!.contains(it.email)) {
          log.info("Skipping {} excluded by email", excludable.name)
          return true
        }
      }
    }

    return false
  }
}
