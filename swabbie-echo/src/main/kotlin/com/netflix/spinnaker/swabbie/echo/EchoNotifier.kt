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

package com.netflix.spinnaker.swabbie.echo

import com.netflix.spinnaker.config.NotificationConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationResult
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationSeverity
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationType
import java.lang.UnsupportedOperationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EchoNotifier(
  private val echoService: EchoService
) : Notifier {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun notify(
    recipient: String,
    notificationContext: Map<String, Any>,
    notificationConfiguration: NotificationConfiguration
  ): NotificationResult {
    notificationConfiguration
      .types
      .forEach { notificationType ->
        when {
          notificationType.equals(NotificationType.EMAIL.name, true) -> {
            return try {
              log.info("Sending notification to $recipient. context: $notificationContext")
              sendEmail(recipient, notificationContext)
              NotificationResult(recipient, NotificationType.EMAIL, success = true)
            } catch (e: Exception) {
              log.error("Failed to send notification to $recipient. context: $notificationContext", e)
              NotificationResult(recipient, NotificationType.EMAIL, success = false)
            }
          }
        }
      }

    throw UnsupportedOperationException("Notification Type not supported in $notificationConfiguration")
  }

  private fun sendEmail(recipient: String, notificationContext: Map<String, Any>) {
    echoService.create(
      EchoService.Notification(
        notificationType = NotificationType.EMAIL,
        to = recipient.split(","),
        severity = NotificationSeverity.HIGH,
        source = EchoService.Notification.Source("swabbie"),
        templateGroup = "swabbie",
        additionalContext = notificationContext
      )
    )
  }
}
