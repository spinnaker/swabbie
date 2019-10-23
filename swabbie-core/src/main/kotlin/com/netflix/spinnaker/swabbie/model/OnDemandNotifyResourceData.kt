package com.netflix.spinnaker.swabbie.model

import com.fasterxml.jackson.annotation.JsonTypeName
import java.time.Instant
import java.time.temporal.ChronoUnit

data class OnDemandNotifyResourceData(
  val resourceId: String,
  var projectedDeletionStamp: Long,
  var markTs: Long?,
  var resourceOwner: String = "swabbie@spinnaker.io",
  val account: String,
  val location: String
)

@JsonTypeName("testResource")
data class NotifyResource(
  override val resourceId: String,
  override val resourceType: String,
  override val cloudProvider: String,
  override val name: String = resourceId,
  override val createTs: Long = Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli(),
  override val grouping: Grouping? = Grouping("group", GroupingType.APPLICATION)
) : Resource() {
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}
