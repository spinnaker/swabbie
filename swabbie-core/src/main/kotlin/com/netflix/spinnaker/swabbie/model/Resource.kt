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
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.repository.LastSeenInfo
import com.netflix.spinnaker.swabbie.tagging.TemporalTags
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.NoSuchElementException
import kotlin.reflect.full.memberProperties

/** Resource Types **/
const val SECURITY_GROUP = "securityGroup"
const val IMAGE = "image"
const val INSTANCE = "instance"
const val LOAD_BALANCER = "loadBalancer"
const val SERVER_GROUP = "serverGroup"
const val LAUNCH_CONFIGURATION = "launchConfiguration"
const val SNAPSHOT = "snapshot"

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
      other.resourceId == this.resourceId && other.resourceType == this.resourceType
  }

  override fun hashCode(): Int {
    return super.hashCode() + this.resourceType.hashCode() + this.resourceId.hashCode()
  }

  override fun toString(): String {
    return details.toString()
  }

  fun tags(): List<BasicTag>? {
    return (details["tags"] as? List<Map<String, Any?>>)?.flatMap {
      it.entries.map { tag ->
        BasicTag(tag.key, tag.value)
      }
    }
  }

  fun getTagValue(key: String): Any? = tags()?.find { it.key == key }?.value

  fun expired(clock: Clock): Boolean {
    tags()?.let { tags ->
      if (tags.any { it.isTemporal() && it.value == "never" }) {
        return false
      }

      return tags.any { expired(it, clock) }
    }

    return false
  }

  fun age(clock: Clock): Duration {
    return Duration.between(Instant.ofEpochMilli(createTs), Instant.now(clock))
  }

  private fun expired(temporalTag: BasicTag, clock: Clock): Boolean {
    if (temporalTag.value == "never" || !temporalTag.isTemporal()) {
      return false
    }

    val (amount, unit) = TemporalTags.toTemporalPair(temporalTag)
    val ttlInDays = Duration.of(amount, unit).toDays()
    return age(clock).toDays() > ttlInDays
  }
}

data class BasicTag(
  val key: String,
  val value: Any?
) {
  fun isTemporal(): Boolean {
    return key in TemporalTags.temporalTags && TemporalTags.supportedTemporalTagValues.any {
      (value as? String)?.matches((it).toRegex()) ?: false
    }
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

  /**
   * Returns the opt-out url for this resource
   * This is used for notifications
   */
  fun optOutUrl(workConfiguration: WorkConfiguration): String {
    return "${workConfiguration.notificationConfiguration.optOutBaseUrl}/${workConfiguration.namespace}/$resourceId/optOut"
  }

  /**
   * Returns a resource specific url
   * This is used for notifications
   */
  fun resourceUrl(workConfiguration: WorkConfiguration): String {
    return "${workConfiguration.notificationConfiguration.resourceUrl}/$resourceId"
  }

  fun matchResourceAttributes(propertyName: String, values: List<Any?>): Boolean {
    return try {
      (getProperty(propertyName) as? Any).matchesAny(values)
    } catch (e: Exception) {
      false
    }
  }

  private fun Any?.matchesAny(values: List<Any?>): Boolean {
    if (this == null || this !is String) {
      return values.contains(this)
    }

    val splitFieldValue = this.split(",").map { it.trim() }

    return values.contains(this) ||
      values.any { it is String && this.patternMatched(it) || splitFieldValue.contains(it) }
  }

  private fun <R : Any?> getProperty(propertyName: String): R {
    try {
      return readPropery(propertyName)
    } catch (e: NoSuchElementException) {
      val details: Map<String, Any?>? = readPropery("details")
      if (details != null && propertyName in details) {
        return details[propertyName] as R
      }

      throw e
    }
  }

  private fun <R : Any?> readPropery(propertyName: String): R {
    @Suppress("UNCHECKED_CAST")
    return javaClass.kotlin.memberProperties.first { it.name == propertyName }.get(this) as R
  }

  fun String.patternMatched(p: String): Boolean =
    p.startsWith("pattern:") && this.contains(p.split(":").last().toRegex())
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

  fun deletionDate(clock: Clock): LocalDate {
    return Instant.ofEpochMilli(projectedDeletionStamp)
      .atZone(clock.zone)
      .toLocalDate()
  }

  fun uniqueId(): String {
    return "$namespace:$resourceId"
  }

  fun withNotificationInfo(
    info: NotificationInfo = NotificationInfo(notificationType = Notifier.NotificationType.NONE.name)
  ): MarkedResource {
    return apply {
      notificationInfo = info
    }
  }

  fun withAdditionalTimeForDeletion(timestampToAdd: Long): MarkedResource {
    return apply {
      projectedDeletionStamp += timestampToAdd
    }
  }
}

data class SlimMarkedResource(
  override val summaries: List<Summary>,
  override val namespace: String,
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
  val notificationStamp: Long? = null
)

data class ResourceState(
  var markedResource: MarkedResource,
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
