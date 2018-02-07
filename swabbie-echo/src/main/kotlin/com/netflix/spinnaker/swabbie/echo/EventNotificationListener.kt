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

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.events.NotifyOwnerEvent
import com.netflix.spinnaker.swabbie.persistence.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Component
class EventNotificationListener(
  private val echoService: EchoService,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val registry: Registry,
  private val clock: Clock
) {
  private val log = LoggerFactory.getLogger(javaClass)
  private val notificationsId = registry.createId("swabbie.echo.notifications")

  @EventListener(NotifyOwnerEvent::class)
  fun onNotifyOwnerEvent(event: NotifyOwnerEvent) {
    val owner = "jchabu@netflix.com" // TODO: populate the event body with owner info
    event.markedResource.let { markedResource ->
      markedResource.notificationInfo.let { notificationInfo ->
        notificationInfo.takeIf {
          notificationInfo.notificationStamp == null && markedResource.adjustedDeletionStamp == null
        }?.let {
          try {
            log.info("Preparing to send notification for {} to user {}", event.markedResource, owner)
            val notificationInstant = Instant.now(clock)
            // offset termination time with when resource was first marked
            val offset: Long = ChronoUnit.MILLIS.between(Instant.ofEpochMilli(markedResource.createdTs!!), notificationInstant)
            log.info("Adjusting deletion time to {}", (offset + markedResource.projectedDeletionStamp).toLocalDate())
            markedResource.apply {
              this.adjustedDeletionStamp = offset + markedResource.projectedDeletionStamp
              this.notificationInfo = NotificationInfo(
                notificationStamp = notificationInstant.toEpochMilli(),
                recipient = owner,
                notificationType = EchoService.Notification.Type.EMAIL.name
              )
            }.let { updatedMarkedResource ->
                val (subject, body) = messageSubjectAndBody(updatedMarkedResource)
                echoService.create(EchoService.Notification(
                  notificationType = EchoService.Notification.Type.EMAIL,
                  to = listOf(owner),
                  cc = listOf(owner),
                  severity = NotificationSeverity.HIGH,
                  source = EchoService.Notification.Source("swabbie"),
                  additionalContext = mapOf(
                    "subject" to subject,
                    "body" to body
                  )
                ))

                log.info("notification sent to {} for {}", listOf(owner), markedResource)
                resourceTrackingRepository.upsert(updatedMarkedResource, updatedMarkedResource.adjustedDeletionStamp!!)
                registry.counter(notificationsId.withTag("result", "success"))
              }
          } catch (e: Exception) {
            log.error("Failed to send notification for resource {}", e)
            registry.counter(notificationsId.withTag("result", "failed"))
          }
        }
      }
    }
  }

  private fun Long.toLocalDate(): LocalDate {
    return Instant.ofEpochMilli(this)
      .atZone(clock.zone)
      .toLocalDate()
  }

  private fun messageSubjectAndBody(markedResource: MarkedResource): EmailSubjectAndBody {
    val optOutUrl = ""// TODO: add endpoint. Configurable
    return EmailSubjectAndBody(
      subject = "Resource ${markedResource.resourceId} scheduled for deletion",
      body = markedResource.summaries
        .joinToString(", ") {
          it.description
        }.let { violationSummary ->
        "<h2>This resource is scheduled to be deleted on ${markedResource.adjustedDeletionStamp!!.toLocalDate()}</h2> <br /> \n " +
          "* $violationSummary <br /> \n" +
          "* Click <a href='$optOutUrl' target='_blank'>here</a> to keep the it for 2 additional weeks."
      }
    )
  }

  data class EmailSubjectAndBody(
    val subject: String,
    val body: String
  )
}
