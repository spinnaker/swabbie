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

import com.netflix.spinnaker.config.LockingConfigurationProperties
import com.netflix.spinnaker.kork.lock.LockManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class LockingService(
  private val lockManager: LockManager,
  private val lockingConfigurationProperties: LockingConfigurationProperties
) : LockManager by lockManager {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  val numLocksCurrentlyHeld = AtomicInteger(0)

  val swabbieMaxLockDuration: Duration
    get() = Duration.ofMillis(lockingConfigurationProperties.maximumLockDurationMillis!!)

  val swabbieMaxConcurrentLocks: Int
    get() = lockingConfigurationProperties.maxConcurrentLocks

  @Synchronized override fun acquireLock(lockOptions: LockManager.LockOptions, onLockAcquiredCallback: Runnable): LockManager.AcquireLockResponse<Void> {
    if (numLocksCurrentlyHeld.get() >= lockingConfigurationProperties.maxConcurrentLocks) {
      log.warn("Too many locks are held " +
          "(held=${numLocksCurrentlyHeld.get()}, wanted=${lockingConfigurationProperties.maxConcurrentLocks}).")
    }
    numLocksCurrentlyHeld.incrementAndGet()
    return lockManager.acquireLock(lockOptions, onLockAcquiredCallback)
  }
}
