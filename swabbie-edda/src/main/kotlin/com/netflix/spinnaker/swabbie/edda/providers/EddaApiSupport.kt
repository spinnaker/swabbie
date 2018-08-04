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

package com.netflix.spinnaker.swabbie.edda.providers

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.EddaApiClient
import com.netflix.spinnaker.swabbie.edda.EddaService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

open class EddaApiSupport(
  private val eddaApiClients: List<EddaApiClient>,
  registry: Registry
) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  val eddaFailureCountId: Id = registry.createId("swabbie.edda.failures")
  fun withEddaClient(region: String, accountId: String): EddaService? {
    eddaApiClients.find {
      it.region == region && it.account.accountId == accountId
    }.let { eddaClient ->
      if (eddaClient == null) {
        log.warn("No edda available for $accountId/$region")
        return null
      }

      return eddaClient.get()
    }
  }
}

const val maxRetries: Int = 3
const val retryBackOffMillis: Long = 5000
