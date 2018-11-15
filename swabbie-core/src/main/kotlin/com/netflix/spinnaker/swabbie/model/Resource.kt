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
import com.netflix.spinnaker.swabbie.exclusions.Excludable
import com.netflix.spinnaker.swabbie.repository.LastSeenInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Resource Types **/
const val SECURITY_GROUP = "securityGroup"
const val IMAGE = "image"
const val INSTANCE = "instance"
const val LOAD_BALANCER = "loadBalancer"
const val SERVER_GROUP = "serverGroup"
const val LAUNCH_CONFIGURATION = "launchConfiguration"

/** Provider Types **/
const val AWS = "aws"

const val RESOURCE_TYPE_INFO_FIELD = "swabbieTypeInfo"
const val NAIVE_EXCLUSION = "swabbieNaiveExclusion"

/**
 * subtypes specify type by annotating with JsonTypeName
 * Represents a resource that swabbie can visit and act on
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
abstract class Resource : Excludable, Timestamped, HasDetails() {
  private val log: Logger = LoggerFactory.getLogger(this.javaClass)

  /**
   * requires all subtypes to be annotated with JsonTypeName
   */
  val swabbieTypeInfo: String = javaClass.getAnnotation(JsonTypeName::class.java).value

  fun withDetail(name: String, value: Any?): Resource =
    this.apply {
      set(name, value)
    }

  fun toLog() =
    "$resourceId:$resourceType"

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

  override fun toString(): String {
    return details.toString()
  }

  fun getTagValue(key: String): String? {
    try {
      val tags = this.details["tags"] as List<Map<String, String>>
      tags.forEach { tag ->
        if (tag.containsKey(key)) {
          return tag.getValue(key)
        }
      }
    } catch (e: ClassCastException) {
      log.warn("Resource {} does not have normal tag format: {}", this.toLog(), this.details["tags"])
    }
    return null
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

  override fun toString(): String {
    return "HasDetails(details=$details)"
  }
}

interface Identifiable : Named {
  val resourceId: String
  val resourceType: String
  val cloudProvider: String
  val grouping: Grouping?
}

data class Grouping(
  val value: String,
  val type: GroupingType
)

enum class GroupingType {
  APPLICATION, PACKAGE_NAME
}

interface Named {
  val name: String?
}

interface Timestamped {
  val createTs: Long
}

interface MarkedResourceInterface : Identifiable {
  val summaries: List<Summary>
  val namespace: String
  var projectedSoftDeletionStamp: Long
  var projectedDeletionStamp: Long
  var notificationInfo: NotificationInfo?
  var markTs: Long?
  var updateTs: Long?
  val resourceOwner: String
  var lastSeenInfo: LastSeenInfo?
}

/**
 * Cleanup candidate decorated with additional metadata
 * 'projectedDeletionStamp' is the scheduled deletion time
 */
data class MarkedResource(
  val resource: Resource,
  override val summaries: List<Summary>,
  override val namespace: String,
  override var projectedSoftDeletionStamp: Long,
  override var projectedDeletionStamp: Long,
  override var notificationInfo: NotificationInfo? = null,
  override var markTs: Long? = null,
  override var updateTs: Long? = null,
  override var resourceOwner: String = "",
  override var lastSeenInfo: LastSeenInfo? = null
) : MarkedResourceInterface, Identifiable by resource {
  fun slim(): SlimMarkedResource {
    return SlimMarkedResource(
      summaries = summaries,
      namespace = namespace,
      projectedSoftDeletionStamp = projectedSoftDeletionStamp,
      projectedDeletionStamp = projectedDeletionStamp,
      notificationInfo = notificationInfo,
      markTs = markTs,
      updateTs = updateTs,
      resourceOwner = resourceOwner,
      resourceId = resource.resourceId,
      resourceType = resource.resourceType,
      cloudProvider = resource.cloudProvider,
      name = resource.name,
      lastSeenInfo = lastSeenInfo,
      grouping = grouping
    )
  }

  fun barebones(): BarebonesMarkedResource {
    return BarebonesMarkedResource(
      namespace = namespace,
      resourceId = resourceId,
      name = name,
      projectedDeletionStamp = projectedDeletionStamp,
      lastSeenInfo = lastSeenInfo,
      summaries = summaries,
      createTs = resource.createTs
    )
  }

  fun uniqueId(): String {
    return "$namespace:$resourceId"
  }
}

data class SlimMarkedResource(
  override val summaries: List<Summary>,
  override val namespace: String,
  override var projectedSoftDeletionStamp: Long,
  override var projectedDeletionStamp: Long,
  override var notificationInfo: NotificationInfo? = null,
  override var markTs: Long? = null,
  override var updateTs: Long? = null,
  override val resourceOwner: String = "",
  override val resourceId: String,
  override val resourceType: String,
  override val cloudProvider: String,
  override val name: String?,
  override var lastSeenInfo: LastSeenInfo? = null,
  override var grouping: Grouping?
) : MarkedResourceInterface

data class BarebonesMarkedResource(
  val namespace: String,
  val resourceId: String,
  val name: String?,
  val projectedDeletionStamp: Long,
  val lastSeenInfo: LastSeenInfo?,
  val summaries: List<Summary>,
  val createTs: Long
)

data class NotificationInfo(
  val recipient: String? = null,
  val notificationType: String? = null,
  val notificationStamp: Long? = null,
  var notificationCount: Int = 0
)

data class ResourceState(
  var markedResource: MarkedResource,
  val softDeleted: Boolean = false,
  val deleted: Boolean = false,
  val optedOut: Boolean = false,
  val statuses: MutableList<Status>,
  val currentStatus: Status? = null
)

data class Status(
  val name: String,
  val timestamp: Long
)

data class ResourceEvaluation(
  val namespace: String,
  val resourceId: String,
  val wouldMark: Boolean,
  val wouldMarkReason: String,
  val summaries: List<Summary>
)

fun MarkedResource.humanReadableDeletionTime(clock: Clock): LocalDate {
  this.projectedDeletionStamp.let {
    return Instant.ofEpochMilli(it)
      .atZone(clock.zone)
      .toLocalDate()
  }
}
