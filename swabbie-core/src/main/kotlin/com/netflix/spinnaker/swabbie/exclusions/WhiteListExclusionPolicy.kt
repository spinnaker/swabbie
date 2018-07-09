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

package com.netflix.spinnaker.swabbie.exclusions

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.Application
import org.springframework.stereotype.Component

@Component
class WhiteListExclusionPolicy(
  front50ApplicationCache: InMemoryCache<Application>,
  accountProvider: AccountProvider
) : ResourceExclusionPolicy {
  private val compositeTypeMapping = mapOf(
   "account" to accountProvider.getAccounts(),
    "application" to front50ApplicationCache.get()
  )

  override fun getType(): ExclusionType = ExclusionType.Whitelist
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): String? {
    keysAndValues(exclusions, ExclusionType.Whitelist).let { kv ->
      if (kv.isEmpty()) {
        return null // no whitelist defined
      }

      kv.keys.forEach { key ->
        val parts = key.split(".")
        val identifier = getIdentifierForType(excludable, parts[0])
        if (identifier != null && parts.size > 1) {
          compositeTypeMapping[parts[0]]?.filter { it.resourceId == identifier }?.forEach { target ->
            findProperty(target, parts[1], kv[key]!!)?.let {
              if (identifier.equals(it, ignoreCase = true)) {
                return null
              }
            }
          }
        } else {
          findProperty(excludable, key, kv[key]!!)?.let {
            return null
          }
        }
      }

      return notWhitelistedMessage(getIdentifierForType(excludable), kv.values.flatten().toSet())
    }
  }

  private fun getIdentifierForType(excludable: Excludable, type: String? = null): String? {
    if (type == "application") {
      return FriggaReflectiveNamer().deriveMoniker(excludable).app?.toLowerCase() ?: ""
    }

    if (type == "account") {
      return excludable.name
    }

    return excludable.resourceId
  }
}
