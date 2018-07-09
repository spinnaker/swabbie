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

import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import org.springframework.stereotype.Component

@Component
class OrphanedImageRule : Rule<AmazonImage> {
  override fun apply(resource: AmazonImage): Result {
    val isReferencedByInstances = resource.details.containsKey(USED_BY_INSTANCES) &&
      resource.details[USED_BY_INSTANCES] as Boolean

    val isReferencedByLaunchConfigs = resource.details.containsKey(USED_BY_LAUNCH_CONFIGURATIONS) &&
      resource.details[USED_BY_LAUNCH_CONFIGURATIONS] as Boolean

    val hasSiblings = resource.details.containsKey(HAS_SIBLINGS_IN_OTHER_ACCOUNTS) &&
      resource.details[HAS_SIBLINGS_IN_OTHER_ACCOUNTS] as Boolean

    if (isReferencedByInstances || isReferencedByLaunchConfigs || hasSiblings) {
      return Result(null)
    }

    return Result(
      Summary(
        description = "Image is not referenced by an instance, a launch config and has no siblings in other accounts",
        ruleName = javaClass.simpleName
      )
    )
  }
}

const val USED_BY_INSTANCES = "usedByInstances"
const val USED_BY_LAUNCH_CONFIGURATIONS = "usedByLaunchConfigurations"
const val HAS_SIBLINGS_IN_OTHER_ACCOUNTS = "hasSiblingsInOtherAccounts"
