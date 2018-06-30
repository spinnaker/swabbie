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
import org.springframework.stereotype.Component

@Component
class Front50ApplicationExclusionPolicy(
  private val front50ApplicationCache: InMemoryCache<Application>
) : ResourceExclusionPolicy {
  private fun findApplication(excludable: Excludable, names: Set<String>): Excludable? {
    FriggaReflectiveNamer().deriveMoniker(excludable).app?.toLowerCase()?.let { appName ->
      return front50ApplicationCache.get().find { matchesApplication(it, appName, names) }
    }

    return null
  }

  private fun matchesApplication(application: Application, name: String, names: Set<String>): Boolean {
    return application.name.equals(name, ignoreCase = true) ||
      names.any { it.equals(application.name, ignoreCase = true) || application.name.matchPattern(it) }
  }

  override fun getType(): ExclusionType = ExclusionType.Application
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): String? {
    keysAndValues(exclusions, ExclusionType.Whitelist).let { kv ->
      findApplication(excludable, kv.values.flatten().toSet())?.let {
        return byPropertyMatchingResult(exclusions, it)
      }
    }

    return null
  }
}
