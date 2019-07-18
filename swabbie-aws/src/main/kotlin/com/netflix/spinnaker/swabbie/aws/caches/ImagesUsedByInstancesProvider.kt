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

import com.netflix.spinnaker.swabbie.CachedViewProvider
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock

class ImagesUsedByInstancesProvider(
  private val clock: Clock,
  private val workConfigurations: List<WorkConfiguration>,
  private val aws: AWS
) : CachedViewProvider<AmazonImagesUsedByInstancesCache>, AWS by aws {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  override fun load(): AmazonImagesUsedByInstancesCache {
    log.info("Loading cache for ${javaClass.simpleName}")
    val refdAmisByRegion = mutableMapOf<String, Set<String>>()
    workConfigurations.asSequence().forEach { w: WorkConfiguration ->
      val instances: Set<AmazonInstance> = getInstances(Parameters(
        region = w.location,
        account = w.account.accountId!!,
        environment = w.account.environment
      ))
        .toSet()

      val refdAmis: Set<String> = instances
        .map { it.imageId }
        .toSet()

      refdAmisByRegion[w.location] = refdAmis.toSet()
    }

    return AmazonImagesUsedByInstancesCache(refdAmisByRegion, clock.millis(), "default")
  }
}
