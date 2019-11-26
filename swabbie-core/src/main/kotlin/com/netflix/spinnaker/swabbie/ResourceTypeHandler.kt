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

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.swabbie.model.OnDemandMarkData
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.ResourceEvaluation
import com.netflix.spinnaker.swabbie.model.WorkConfiguration

interface ResourceTypeHandler<T : Resource> {
  /**
   * Determines if a handler can handle the [WorkConfiguration].
   */
  fun handles(workConfiguration: WorkConfiguration): Boolean

  /**
   * Gets cleanup candidates.
   * Perform any needed data massaging, add metadata that can be used in a [Rule].
   * Implementations should not filter out candidates, leave that to [ExclusionPolicy].
   */
  fun getCandidates(workConfiguration: WorkConfiguration): List<T>?

  /**
   * Fetches a single resource.
   * Decorate metadata that can be used in a [Rule]
   */
  fun getCandidate(resourceId: String, resourceName: String = resourceId, workConfiguration: WorkConfiguration): T?

  /**
   * Marks a single marked resource matching the granularity of [WorkConfiguration].
   */
  fun mark(workConfiguration: WorkConfiguration)

  /**
   * Notifies resource owners
   */
  fun notify(workConfiguration: WorkConfiguration)

  /**
   * Deletes marked resources matching the granularity of [WorkConfiguration].
   */
  fun delete(workConfiguration: WorkConfiguration)

  /**
   * Opts a resource out whether or not it has been marked.
   */
  fun optOut(resourceId: String, workConfiguration: WorkConfiguration): ResourceState

  /**
   * Returns a list of resource violations from applying rules
   */
  fun getViolations(resource: T, workConfiguration: WorkConfiguration): List<Summary>

  /**
   * Used to check references and augment candidates for further processing
   * Impl should prefer this interface and avoid I/O in [Rule]
   * A rule should leverage metadata added by this function.
   */
  fun preProcessCandidates(candidates: List<T>, workConfiguration: WorkConfiguration): List<T>

  /**
   * Decides whether a single candidate will be marked, and returns information about that decision.
   */
  fun evaluateCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): ResourceEvaluation

  /**
   * Admin api to recalculate deletion timestamp for [numResources] to [retentionSeconds] from now
   */
  fun recalculateDeletionTimestamp(namespace: String, retentionSeconds: Long, numResources: Int)

  /**
   * FOR TESTING
   * Marks a single resource w/o checking if it should be marked.
   */
  fun markResource(resourceId: String, onDemandMarkData: OnDemandMarkData, workConfiguration: WorkConfiguration)

  /**
   * FOR TESTING
   * Delete a single resource w/o checking if it's still a candidate for deletion
   */
  fun deleteResource(resourceId: String, workConfiguration: WorkConfiguration)
}
