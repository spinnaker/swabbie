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

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.swabbie.exclusions.Excludable
import com.netflix.spinnaker.swabbie.exclusions.ExclusionPolicy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Resource Types **/
const val SECURITY_GROUP = "securityGroup"
const val LOAD_BALANCER = "loadBalancer"
const val SERVER_GROUP = "serverGroup"

/** Provider Types **/
const val AWS = "aws"

const val RESOURCE_TYPE_INFO_FIELD = "swabbieTypeInfo"

/**
 * subtypes specify type by annotating with JsonTypeName
 * Represents a resource that swabbie can visit and act on
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
abstract class Resource : Identifiable, Excludable, HasDetails() {
  /**
   * requires all subtypes to be annotated with JsonTypeName
   */
  val swabbieTypeInfo: String = javaClass.getAnnotation(JsonTypeName::class.java).value

  /**
   * Determines if a resource should be spared by Swabbie
   */
  override fun shouldBeExcluded(exclusionPolicies: List<ExclusionPolicy>, exclusions: List<Exclusion>): Boolean =
    exclusionPolicies.find { it.apply(this, exclusions) } != null


  fun withDetail(name: String, value: Any?): Resource =
    this.apply {
      set(name, value)
    }

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

/**
 * The details field will include all non strongly typed fields of the Resource
 */
abstract class HasDetails {
  val details: MutableMap<String, Any?> = mutableMapOf()

  @JsonAnySetter
  fun set(name: String, value: Any?) {
    details[name] = value
  }

  @JsonAnyGetter
  fun details() = details
}

interface Identifiable : Named {
  val resourceId: String
  val resourceType: String
  val cloudProvider: String
}

interface Named {
  val name: String
}

/**
 * Cleanup candidate decorated with additional metadata
 * 'adjustedDeletionStamp' is the scheduled deletion time
 */
data class MarkedResource(
  val resource: Resource,
  val summaries: List<Summary>,
  val namespace: String,
  var projectedDeletionStamp: Long,
  var notificationInfo: NotificationInfo? = null,
  var createdTs: Long? = null,
  var updateTs: Long? = null,
  val resourceOwner: String? = null
) : Identifiable by resource

data class NotificationInfo(
  val recipient: String? = null,
  val notificationType: String? = null,
  val notificationStamp: Long? = null
)

data class ResourceState(
  var markedResource: MarkedResource,
  val deleted: Boolean = false,
  val statuses: MutableList<Status>,
  val currentStatus: Status = statuses.last()
)

data class Status(
  val name: String,
  val timestamp: Long
)

fun MarkedResource.humanReadableDeletionTime(clock: Clock): LocalDate {
  this.projectedDeletionStamp.let {
    return Instant.ofEpochMilli(it)
      .atZone(clock.zone)
      .toLocalDate()
  }
}
