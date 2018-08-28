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

package com.netflix.spinnaker.swabbie.krieger

import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.Application
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.*

@Component
class KriegerApplicationService(
  private val kriegerService: Optional<KriegerService>
) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  fun getApplications(): Set<Application> {
    if (kriegerService.isPresent) {
      return kriegerService.get()
        .getApplications()
        ._embedded["applications"]
        .orEmpty()
        .toSet()
    }

    log.debug("KRIEGER not enabled!!")
    return emptySet()
  }
}

@Component
class KriegerApplicationCache(
  kriegerApplicationService: KriegerApplicationService
) : InMemoryCache<Application>(
  kriegerApplicationService.getApplications()
)

