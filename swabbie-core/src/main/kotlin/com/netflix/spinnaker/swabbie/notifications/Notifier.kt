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

package com.netflix.spinnaker.swabbie.notifications

import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.WorkConfiguration

interface Notifier {
  fun notify(envelope: Envelope): NotificationResult

  enum class NotificationType {
    EMAIL,
    NONE
  }

  enum class NotificationSeverity {
    NORMAL,
    HIGH
  }

  data class NotificationResult(
    val recipient: String,
    val notificationType: NotificationType = NotificationType.NONE,
    val success: Boolean = false
  )

  data class Envelope(
    val recipient: String,
    val resources: List<Pair<MarkedResource, WorkConfiguration>>
  )
}
