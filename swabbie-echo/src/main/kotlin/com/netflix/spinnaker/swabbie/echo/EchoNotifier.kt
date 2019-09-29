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

import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationResult
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationType
import com.netflix.spinnaker.swabbie.notifications.Notifier.NotificationSeverity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.lang.UnsupportedOperationException

@Component
class EchoNotifier(
  private val echoService: EchoService,
  private val retrySupport: RetrySupport
) : Notifier {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val timeoutMillis: Long = 5000
  private val maxAttempts: Int = 3

  override fun notify(
    envelope: Notifier.Envelope
  ): NotificationResult {
    val recipient = envelope.recipient
    // Notifications are grouped by resource type, they all have a common notification setting.
    val workConfiguration = envelope
      .resources.map {
        it.second
      }.first()

    val notificationConfig = workConfiguration.notificationConfiguration
    notificationConfig.types.forEach { notificationType ->
      when {
        notificationType.equals(NotificationType.EMAIL.name, true) -> {
          try {
            retrySupport.retry({
              echoService.create(
                EchoService.Notification(
                  notificationType = NotificationType.EMAIL,
                  to = recipient.split(","),
                  severity = NotificationSeverity.HIGH,
                  source = EchoService.Notification.Source("swabbie"),
                  templateGroup = "swabbie",
                  additionalContext = notificationContext(recipient, envelope.resources, workConfiguration.resourceType)
                )
              )
            }, maxAttempts, timeoutMillis, false)
            return NotificationResult(recipient, NotificationType.EMAIL, success = true)
          } catch (e: Exception) {
            log.error("Failed to send notification", e)
            return NotificationResult(recipient, NotificationType.EMAIL, success = false)
          }
        }
      }
    }

    throw UnsupportedOperationException("Notification Type not supported in $workConfiguration")
  }

  private fun String.unCamelCase(): String =
    split("(?=[A-Z])".toRegex()).joinToString(" ").toLowerCase()

  private fun notificationContext(
    recipient: String,
    resources: List<Pair<MarkedResource, WorkConfiguration>>,
    resourceType: String
  ): Map<String, Any> {
    return mapOf(
      "resourceType" to resourceType.unCamelCase(), // TODO: Jeyrs - normalize resource type so we wont have to do this
      "resourceOwner" to recipient,
      "resources" to resources
    )
  }
}
