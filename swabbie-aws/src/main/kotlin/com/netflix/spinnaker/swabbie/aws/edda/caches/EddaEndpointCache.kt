/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.aws.edda.caches

import com.netflix.spinnaker.security.AuthenticatedRequest.allowAnonymous
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.aws.edda.EddaEndpointsService
import com.netflix.spinnaker.swabbie.model.EddaEndpoint

class EddaEndpointCache(
  private val eddaEndpointsService: EddaEndpointsService
) : InMemoryCache<EddaEndpoint>({
  allowAnonymous { load(eddaEndpointsService) }
}) {
  companion object {
    fun load(eddaEndpointsService: EddaEndpointsService): Set<EddaEndpoint> {
      return eddaEndpointsService.getEddaEndpoints().get()
        .asSequence()
        .mapNotNull { buildEndpoint(it) }
        .toSet()
    }

    // i.e. "http://edda-account.region.foo.bar.com",
    private fun buildEndpoint(endpoint: String): EddaEndpoint? {
      val regex =
        """^https?://edda-([\w\-]+)\.([\w\-]+)\.([\w\-]+)\..*$""".toRegex()
      val match = regex.matchEntire(endpoint) ?: return null
      val (account, region, env) = match.destructured

      return EddaEndpoint(region, account, env, endpoint, "$region-$account-$env")
    }
  }
}
