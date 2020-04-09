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
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class RedisUsedResourceRepository(
  redisClientSelector: RedisClientSelector
) : UsedResourceRepository {

  private val redisClientDelegate: RedisClientDelegate = redisClientSelector.primary("default")
  private val log = LoggerFactory.getLogger(javaClass)

  @Value("\${swabbie.repository.used-resource-repository.retention-ttl:172800}")
  private var expTime = TimeUnit.DAYS.toSeconds(2).toInt()

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  override fun recordUse(resourceType: String, id: String, namespace: String) {
    redisClientDelegate.withCommandsClient { client ->
      client.setex(makeKey(resourceType, id), expTime, namespace)
    }
  }

  override fun isUsed(resourceType: String, id: String, namespace: String): Boolean {
    return redisClientDelegate.withCommandsClient<Boolean> { client ->
      val ns = client.get(makeKey(resourceType, id))
      ns != null && ns == namespace
    }
  }

  private fun makeKey(resourceType: String, id: String): String {
    return "{swabbie:used:$resourceType}:$id"
  }
}
