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

import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.model.WorkItem
import com.netflix.spinnaker.swabbie.work.WorkQueue
import java.util.concurrent.LinkedBlockingQueue
import org.slf4j.LoggerFactory

// For testing purpose
class InMemoryWorkQueue(
  private val _seed: List<WorkConfiguration> = emptyList()
) : WorkQueue {

  private val log = LoggerFactory.getLogger(javaClass)
  private val _q = LinkedBlockingQueue<WorkItem>()

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun seed() {
    _seed.map { it.toWorkItems() }.flatten().forEach {
      _q.put(it)
    }
  }

  override fun pop(): WorkItem? {
    return _q.poll()
  }

  override fun push(workItem: WorkItem) {
    _q.put(workItem)
  }

  override fun isEmpty(): Boolean {
    return _q.isEmpty()
  }

  override fun size(): Int {
    return _q.size
  }
}
