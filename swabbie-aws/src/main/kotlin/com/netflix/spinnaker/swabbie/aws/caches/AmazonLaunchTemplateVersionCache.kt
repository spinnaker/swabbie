/*
 *
 *  Copyright 2020 Netflix, Inc.
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
import com.netflix.spinnaker.swabbie.aws.launchtemplates.AmazonLaunchTemplateVersion

open class AmazonLaunchTemplateVersionInMemoryCache(
  private val provider: CachedViewProvider<AmazonLaunchTemplateVersionCache>
) : InMemorySingletonCache<AmazonLaunchTemplateVersionCache>(
  { AuthenticatedRequest.allowAnonymous(provider::load) }
)

data class AmazonLaunchTemplateVersionCache(
  private val refdAmisByRegion: Map<String, Map<String, Set<AmazonLaunchTemplateVersion>>>,
  private val lastUpdated: Long,
  override val name: String?
) : Cacheable {
  fun getLaunchTemplateVersionsByRegionForImage(params: Parameters): Set<AmazonLaunchTemplateVersion> {
    if (params.region != "" && params.id != "") {
      return getRefdAmisForRegion(params.region).getOrDefault(params.id, emptySet())
    } else {
      throw IllegalArgumentException("Missing required region and id parameters")
    }
  }

  /**
   * @param region: AWS region
   *
   * Returns a map of <K: all ami's referenced by a launch template in region, V: set of launch templates referencing K>
   */
  fun getRefdAmisForRegion(region: String): Map<String, Set<AmazonLaunchTemplateVersion>> {
    return refdAmisByRegion[region].orEmpty()
  }

  fun getLastUpdated(): Long {
    return lastUpdated
  }
}
