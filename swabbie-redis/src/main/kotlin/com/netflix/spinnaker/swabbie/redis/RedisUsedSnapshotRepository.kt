/*
 *
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.redis

import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.swabbie.repository.UsedSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class RedisUsedSnapshotRepository(
  redisClientSelector: RedisClientSelector
) : UsedSnapshotRepository {

  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val log = LoggerFactory.getLogger(javaClass)

  private val SNAP_USED = "{swabbie:snapshot:used}"
  private val expTime = TimeUnit.DAYS.toSeconds(2).toInt()

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun recordUse(snapshotId: String, namespace: String) {
    redisClientDelegate.withCommandsClient { client ->
      client.setex("$SNAP_USED:$snapshotId", expTime, namespace) //todo eb: is this the right thing to store?
    }
  }

  override fun isUsed(snapshotId: String, namespace: String): Boolean {
    return redisClientDelegate.withCommandsClient<Boolean> { client ->
      val exists = client.get("$SNAP_USED:$snapshotId")
      exists != null
    }
  }
}