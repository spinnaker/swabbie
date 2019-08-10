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

package com.netflix.spinnaker.swabbie.redis

import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.model.WorkItem
import com.netflix.spinnaker.swabbie.work.WorkQueue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Stores an list of [com.netflix.spinnaker.swabbie.model.WorkItem]
 * @property _seed work configurations defined in the application config from which to derive work items
 * @see [com.netflix.spinnaker.swabbie.work.WorkQueueManager]
 */
@Component
class RedisWorkQueue(
  redisClientSelector: RedisClientSelector,
  private val _seed: List<WorkConfiguration>
) : WorkQueue {
  private val log = LoggerFactory.getLogger(javaClass)
  private val workItems: List<WorkItem> = _seed.map { it.toWorkItems() }.flatten()
  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun seed() {
    log.debug("Seeding work queue with ${workItems.map { "${it.action} in ${it.id}" }}")
    workItems.forEach {
      push(it)
    }
  }

  /**
   * Pops a [WorkItem] off of the [WorkQueue]
   */
  override fun pop(): WorkItem? {
    redisClientDelegate.withCommandsClient<String> { client ->
      client.spop(WORK_KEY)
    }?.let { id ->
      return workItems.find { id == it.id }
    }

    return null
  }

  override fun push(workItem: WorkItem) {
    redisClientDelegate.withCommandsClient { client ->
      client.sadd(WORK_KEY, workItem.id)
    }
  }

  override fun isEmpty(): Boolean = size() == 0

  override fun size(): Int {
    return redisClientDelegate.withCommandsClient<Set<String>> { client ->
      client.smembers(WORK_KEY)
    }.size
  }
}

private const val WORK_KEY = "{swabbie:work}"
