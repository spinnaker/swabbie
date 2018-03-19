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
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.Resource
import org.springframework.stereotype.Component

@Component
class Front50ApplicationResourceOwnerResolutionStrategy(
  private val front50ApplicationCache: InMemoryCache<Application>
) : ResourceOwnerResolutionStrategy {
  override fun resolve(resource: Resource): String? =
    FriggaReflectiveNamer().deriveMoniker(resource).app?.let { derivedApp ->
      front50ApplicationCache.get().find { it.name.equals(derivedApp, ignoreCase = true) }?.email
    }
}
