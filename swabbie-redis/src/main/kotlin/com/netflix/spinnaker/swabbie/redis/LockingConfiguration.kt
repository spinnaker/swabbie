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

package com.netflix.spinnaker.swabbie.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.LockingConfigurationProperties
import com.netflix.spinnaker.kork.jedis.RedisClientSelector
import com.netflix.spinnaker.kork.jedis.lock.RedisLockManager
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.swabbie.LockingService
import java.time.Clock
import java.util.Optional
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(LockingConfigurationProperties::class)
open class LockingConfiguration(
  private val lockingConfigurationProperties: LockingConfigurationProperties
) {

  @Bean
  @ConditionalOnProperty("locking.enabled")
  open fun lockingService(lockManager: LockManager): LockingService {
    return LockingService(lockManager, lockingConfigurationProperties)
  }

  @Bean
  @ConditionalOnMissingBean(LockManager::class)
  open fun noopLockingService(): LockingService {
    return LockingService.NOOP()
  }

  @Bean
  @ConditionalOnProperty("locking.enabled")
  open fun lockManager(
    clock: Clock,
    registry: Registry,
    objectMapper: ObjectMapper,
    redisClientSelector: RedisClientSelector
  ): RedisLockManager {
    return RedisLockManager(
      null, // Will default to node name
      clock,
      registry,
      objectMapper,
      redisClientSelector.primary("default"),
      Optional.of(lockingConfigurationProperties.heartbeatRateMillis),
      Optional.of(lockingConfigurationProperties.leaseDurationMillis)
    )
  }
}
