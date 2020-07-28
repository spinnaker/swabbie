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

package com.netflix.spinnaker.swabbie.aws.images

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import org.springframework.stereotype.Component

/**
 * Images are marked when they are orphaned.
 *
 * The `outOfUseThresholdDays` property controls the amount of time
 *  we let a resource be unseen (out of use) before it is marked and deleted.
 * For example, if `outOfUseThresholdDays = 10`, then an image is allowed to sit in
 *  orphaned state (defined by the rules below) for 10 days before it will be marked.
 */
@Component
class OrphanedImageRule : Rule {
  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = AmazonImage::class.java.isAssignableFrom(clazz)
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (resource !is AmazonImage) {
      return Result(null)
    }

    if (resource.matchesAnyRule(
      USED_BY_INSTANCES,
      USED_BY_LAUNCH_CONFIGURATIONS,
      HAS_SIBLINGS_IN_OTHER_ACCOUNTS,
      SEEN_IN_USE_RECENTLY
    )
    ) {
      return Result(null)
    }

    return Result(
      Summary(
        description = "Image is not referenced by an Instance, Launch Configuration, " +
          "and has no siblings in other accounts, " +
          "and has been that way for over the outOfUseThreshold days",
        ruleName = name()
      )
    )
  }

  private fun AmazonImage.matchesAnyRule(vararg ruleName: String): Boolean {
    return ruleName.any { details.containsKey(it) && details[it] as Boolean }
  }
}

const val USED_BY_INSTANCES = "usedByInstances"
const val USED_BY_LAUNCH_CONFIGURATIONS = "usedByLaunchConfigurations"
const val HAS_SIBLINGS_IN_OTHER_ACCOUNTS = "hasSiblingsInOtherAccounts"
const val SEEN_IN_USE_RECENTLY = "seenInUseRecently"
