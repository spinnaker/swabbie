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

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.swabbie.model.MarkedResource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

//TODO: move to tagging classes
fun tagMessage(markedResource: MarkedResource, clock: Clock): String {
  val optOutUrl = ""// TODO: add endpoint. Configurable
  return "Resource ${markedResource.resourceId} scheduled for deletion\n" +
    markedResource.summaries
      .joinToString(", ") {
        it.description
      }.let { violationSummary ->
        "<h2>This resource is scheduled to be deleted on ${(markedResource.adjustedDeletionStamp?: markedResource.projectedDeletionStamp).toLocalDate(clock)}</h2> <br /> \n " +
          "* $violationSummary <br /> \n" +
          "* Click <a href='$optOutUrl' target='_blank'>here</a> to keep the it for 2 additional weeks."
      }
}

//TODO: move email template to echo
fun messageSubjectAndBody(markedResource: MarkedResource, clock: Clock): EmailSubjectAndBody {
  val optOutUrl = ""// TODO: add endpoint. Configurable
  return EmailSubjectAndBody(
    subject = "Resource ${markedResource.resourceId} scheduled for deletion",
    body = markedResource.summaries
      .joinToString(", ") {
        it.description
      }.let { violationSummary ->
        "<h2>This resource is scheduled to be deleted on ${(markedResource.adjustedDeletionStamp?: markedResource.projectedDeletionStamp).toLocalDate(clock)}</h2> <br /> \n " +
          "* $violationSummary <br /> \n" +
          "* Click <a href='$optOutUrl' target='_blank'>here</a> to keep the it for 2 additional weeks."
      }
  )
}

data class EmailSubjectAndBody(
  val subject: String,
  val body: String
)

private fun Long.toLocalDate(clock: Clock): LocalDate {
  return Instant.ofEpochMilli(this)
    .atZone(clock.zone)
    .toLocalDate()
}
