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

package com.netflix.spinnaker.swabbie.aws.caches

import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.CachedViewProvider
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import java.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ImagesUsedByInstancesProvider(
  private val clock: Clock,
  private val accountProvider: AccountProvider,
  private val aws: AWS
) : CachedViewProvider<AmazonImagesUsedByInstancesCache>, AWS by aws {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun load(): AmazonImagesUsedByInstancesCache {
    log.info("Loading cache for ${javaClass.simpleName}")
    val refdAmisByRegion = mutableMapOf<String, MutableSet<String>>()
    accountProvider.getAccounts()
      .filter { it.cloudProvider == "aws" && !it.regions.isNullOrEmpty() }
      .forEach { account ->
        account.regions!!.forEach { region ->
          // we need to read all the instances in every region, no matter if it's deprecated or not,
          // because we need to see all the AMIs that are in use.
          log.info("Reading instances in {}/{}/{}", account.accountId, region.name, account.environment)
          val instances: List<AmazonInstance> = getInstances(
            Parameters(
              region = region.name,
              account = account.accountId!!,
              environment = account.environment
            )
          )

          val refdAmis: Set<String> = instances
            .map { it.imageId }
            .toSet()

          val currentAmis = refdAmisByRegion.getOrDefault(region.name, mutableSetOf())
          currentAmis.addAll(refdAmis)
          refdAmisByRegion[region.name] = currentAmis
        }
      }

    return AmazonImagesUsedByInstancesCache(refdAmisByRegion, clock.millis(), "default")
  }
}
