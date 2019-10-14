/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.test

import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.NotificationTask
import java.util.concurrent.LinkedBlockingDeque

// For local testing. Implemented as a mere list
class InMemoryNotificationQueue : NotificationQueue {
  private val _q = LinkedBlockingDeque<NotificationTask>()
  override fun popAll(): List<NotificationTask> {
    val list = mutableListOf<NotificationTask>()
    while (!_q.isEmpty()) {
      list += _q.pop()
    }

    return list
  }

  override fun isEmpty(): Boolean {
    return _q.isEmpty()
  }

  override fun add(notificationTask: NotificationTask) {
    _q.add(notificationTask)
  }
}
