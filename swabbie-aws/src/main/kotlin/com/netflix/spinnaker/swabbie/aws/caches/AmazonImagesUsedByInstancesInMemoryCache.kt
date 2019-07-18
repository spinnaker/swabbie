/*
 *
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.aws.caches

import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.swabbie.Cacheable
import com.netflix.spinnaker.swabbie.CachedViewProvider
import com.netflix.spinnaker.swabbie.InMemorySingletonCache
import com.netflix.spinnaker.swabbie.aws.Parameters
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
open class AmazonImagesUsedByInstancesInMemoryCache(
  provider: CachedViewProvider<AmazonImagesUsedByInstancesCache>
) : InMemorySingletonCache<AmazonImagesUsedByInstancesCache>({ AuthenticatedRequest.allowAnonymous(provider::load) })

data class AmazonImagesUsedByInstancesCache(
  private val refdAmisByRegion: Map<String, Set<String>>,
  private val lastUpdated: Long,
  override val name: String?
) : Cacheable {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  /**
   * @param params.region: return a Set<String> of Amazon imageIds (i.e. "ami-abc123")
   * currently referenced by running EC2 instances in the region
   */
  fun getAll(params: Parameters): Set<String> {
    if (params.region != "") {
      return refdAmisByRegion[params.region] ?: emptySet()
    } else {
      throw IllegalArgumentException("Missing required region parameter")
    }
  }

  fun getLastUpdated(): Long {
    return lastUpdated
  }
}
