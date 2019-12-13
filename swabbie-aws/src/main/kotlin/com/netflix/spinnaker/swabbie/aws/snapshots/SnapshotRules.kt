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

package com.netflix.spinnaker.swabbie.aws.snapshots

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import org.springframework.stereotype.Component

/**
 * Snapshots with a specific naming pattern are created automatically by the bakery when
 *  the image is created. Once an image is deleted we can safely clean up the snapshot.
 */
@Component
class OrphanedSnapshotRule : Rule {
  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean = AmazonSnapshot::class.java.isAssignableFrom(clazz)
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (resource !is AmazonSnapshot || resource.matchesAnyRule(
        IMAGE_EXISTS
      )) {
      return Result(null)
    }

    return Result(
      Summary(
        description = "This snapshot was auto-generated at bake time and the image no longer exists",
        ruleName = name()
      )
    )
  }

  private fun AmazonSnapshot.matchesAnyRule(vararg ruleName: String): Boolean {
    return ruleName.any { details.containsKey(it) && details[it] as Boolean }
  }
}

const val IMAGE_EXISTS = "imageExists"
