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
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.model.Application
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

@Component
class Front50ApplicationResourceOwnerResolver(
  private val front50Service: Front50Service
) : ResourceOwnerResolver {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private var applicationsCache = AtomicReference<Set<Application>>()

  override fun resolve(resource: Resource): String? =
    FriggaReflectiveNamer().deriveMoniker(resource).app?.let { derivedApp ->
      applicationsCache.get().find { it.name == derivedApp }?.email
    }

  @Scheduled(fixedDelay = 24 * 60 * 60 * 1000L)
  private fun refreshApplications() {
    try {
      applicationsCache.set(front50Service.getApplications())
    } catch (e: Exception) {
      log.error("Error refreshing applications cache", e)
    }
  }
}
