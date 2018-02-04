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

package com.netflix.spinnaker.swabbie.model

import com.fasterxml.jackson.annotation.JsonTypeInfo

/** Resource Types **/
const val SECURITY_GROUP = "securityGroup"

/** Provider Types **/
const val AWS = "aws"

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.PROPERTY)
abstract class Resource: Identifiable {
  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }

    return other is Resource &&
      other.resourceId == this.resourceId && other.resourceType == this.resourceId
  }

  override fun hashCode(): Int {
    return super.hashCode() + this.resourceType.hashCode() + this.resourceId.hashCode()
  }
}

interface Identifiable {
  val resourceId: String
  val resourceType: String
  val cloudProvider: String
}

data class MarkedResource(
  val resource: Resource,
  val summaries: List<Summary>,
  val configurationId: String,
  val projectedDeletionStamp: Long,
  var adjustedDeletionStamp: Long? = null,
  var notificationInfo: NotificationInfo = NotificationInfo(),
  var createdTs: Long? = null,
  var updateTs: Long? = null
): Identifiable by resource

data class NotificationInfo(
  val recipient: String? = null,
  val notificationType: String? = null,
  val notificationStamp: Long? = null
)

data class ResourceState(
  var markedResource: MarkedResource,
  val deleted: Boolean = false,
  val statuses: MutableList<Status>
)

data class Status(
  val name: String,
  val timestamp: Long
): Comparable<Status> {
  override fun compareTo(other: Status): Int {
    return if (other.name == name) {
      timestamp.compareTo(other.timestamp)
    } else {
      1
    }
  }
}
