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

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import com.netflix.spinnaker.swabbie.Dates

@JsonTypeName("amazonSnapshot")
data class AmazonSnapshot(
  val volumeId: String,
  val state: String,
  val progress: String,
  val volumeSize: Int,
  val startTime: Long,
  val description: String?,
  val snapshotId: String,
  val ownerId: String,
  val encrypted: Boolean,
  val ownerAlias: String?,
  val stateMessage: String?,
  override val resourceId: String = snapshotId,
  override val resourceType: String = SNAPSHOT,
  override val cloudProvider: String = AWS,
  override val name: String = snapshotId,
  private val creationDate: String? = Dates.toCreationDate(startTime)
) : AmazonResource(creationDate) {
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}
