package com.netflix.spinnaker.swabbie.model

import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.repository.LastSeenInfo

data class OnDemandMarkData(
  var projectedSoftDeletionStamp: Long,
  var projectedDeletionStamp: Long,
  var markTs: Long? = null,
  var resourceOwner: String = "swabbie@spinnaker.io",
  var notificationInfo: NotificationInfo? = NotificationInfo(
    recipient = resourceOwner,
    notificationType = Notifier.NotificationType.EMAIL.name,
    notificationCount = 1
  ),
  var lastSeenInfo: LastSeenInfo? = null
)
