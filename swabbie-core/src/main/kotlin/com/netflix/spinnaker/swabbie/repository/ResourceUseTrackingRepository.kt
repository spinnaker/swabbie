/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.repository

interface ResourceUseTrackingRepository {
  /**
   * record that resource is used by another resource at this exact time
   */
  fun recordUse(resourceIdentifier: String, usedByResourceIdentifier: String)

  /**
   * gets resources that haven't been seen in use for X days
   */
  fun getUnused(): List<LastSeenInfo>

  fun isUnused(resourceIdentifier: String): Boolean

  /**
   * Returns true if data is present for the whole outOfUseThreshold period.
   * Returns false if data is only present for part of the period,
   *  i.e. if the outOfUseThreshold is 10 days but we only have data for 5.
   */
  fun hasCompleteData(): Boolean
}

data class LastSeenInfo(
  val resourceIdentifier: String,
  val usedByResourceIdentifier: String,
  val timeSeen: Long
)
