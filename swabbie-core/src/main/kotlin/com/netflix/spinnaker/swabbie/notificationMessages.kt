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

import com.netflix.spinnaker.swabbie.events.typeAndName
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.humanReadableDeletionTime
import java.time.Clock

class NotificationMessage {
  companion object {
    fun subject(messageType: MessageType, clock: Clock, vararg markedResources: MarkedResource): String {
      if (messageType == MessageType.EMAIL) {
        return "${markedResources.size} resource(s) scheduled to be cleaned up on ${markedResources[0].humanReadableDeletionTime(clock)}"
      }

      return ""
    }

    fun body(messageType: MessageType, clock: Clock, optOutUrl: String, vararg markedResources: MarkedResource): String {
      if (messageType == MessageType.TAG) {
        return markedResources[0].summaries.joinToString(", ") {
          it.description
        }.let { summary ->
            val time = markedResources[0].humanReadableDeletionTime(clock)
            "Scheduled to be cleaned up on $time<br /> \n " +
              "* $summary <br /> \n" +
              "* Click <a href='$optOutUrl' target='_blank'>here</a> to keep the it for 2 additional weeks."
          }
      } else { //TODO: probably move to a template
        return markedResources.map { m: MarkedResource ->
          m.summaries.joinToString(", ") {
            it.description
          }.also { summary ->
              "${m.typeAndName()} scheduled to be janitored on ${m.humanReadableDeletionTime(clock)}</h2><br /> \n " +
                "* $summary <br /> \n" +
                "* Click <a href='$optOutUrl' target='_blank'>here</a> to keep the it for 2 additional weeks."
            }
        }.joinToString("\n")
      }
    }
  }
}

enum class MessageType {
  TAG, EMAIL
}
