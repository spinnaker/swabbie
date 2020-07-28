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
import com.netflix.spinnaker.swabbie.Dates
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import java.time.Instant
import java.time.ZoneId

@JsonTypeName("amazonSnapshot")
class AmazonSnapshot(
  val volumeId: String,
  val state: String,
  val progress: String,
  val volumeSize: Int,
  startTime: Any,
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
  creationDate: String? = getCreationDate(startTime)
) : AmazonResource(creationDate) {
  val startTime: Long = convertStartTime(startTime)

  /**
   * Amazon returns [startTime] as a String (date), whereas edda returns it as a Long.
   * This allows either of those formats to be accepted and used successfully.
   */
  companion object {
    private fun convertStartTime(value: Any): Long {
      return when (value) {
        is String ->
          Dates
            .toLocalDateTime(value)
            .toInstant(ZoneId.systemDefault().rules.getOffset(Instant.now()))
            .toEpochMilli()
        is Long -> value
        else -> throw IllegalArgumentException("Start time must be String (date) or Long, but given ${value.javaClass}")
      }
    }

    private fun getCreationDate(value: Any): String? {
      return when (value) {
        is String -> value
        is Long -> Dates.toCreationDate(value)
        else -> throw IllegalArgumentException("Start time must be String (date) or Long, but given ${value.javaClass}")
      }
    }
  }
}
