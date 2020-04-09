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
import java.time.Duration
import java.util.concurrent.Callable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

open class LockingService(
  private val lockManager: LockManager,
  private val lockingConfigurationProperties: LockingConfigurationProperties
) : LockManager by lockManager {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  init {
    if (lockManager is NoopLockManager) {
      log.warn("Locking is not enabled...")
    }
  }

  val swabbieMaxLockDuration: Duration
    get() = Duration.ofMillis(lockingConfigurationProperties.maximumLockDurationMillis!!)

  class NOOP : LockingService(NoopLockManager(), LockingConfigurationProperties())
}

class NoopLockManager : LockManager {
  override fun tryCreateLock(lockOptions: LockManager.LockOptions?): LockManager.Lock {
    return LockManager.Lock(null, null, 0, 0, 0, 0, 0, null)
  }

  override fun releaseLock(lock: LockManager.Lock, wasWorkSuccessful: Boolean): Boolean = true
  override fun <R : Any?> acquireLock(
    lockOptions: LockManager.LockOptions,
    onLockAcquiredCallback: Callable<R>
  ): LockManager.AcquireLockResponse<R> {
    onLockAcquiredCallback.call()
    return LockManager.AcquireLockResponse<R>(null, null, LockManager.LockStatus.ACQUIRED, null, true)
  }

  override fun acquireLock(
    lockOptions: LockManager.LockOptions,
    onLockAcquiredCallback: Runnable
  ): LockManager.AcquireLockResponse<Void> {
    onLockAcquiredCallback.run()
    return LockManager.AcquireLockResponse<Void>(null, null, LockManager.LockStatus.ACQUIRED, null, true)
  }

  override fun <R : Any?> acquireLock(
    lockName: String,
    maximumLockDurationMillis: Long,
    onLockAcquiredCallback: Callable<R>
  ): LockManager.AcquireLockResponse<R> {
    onLockAcquiredCallback.call()
    return LockManager.AcquireLockResponse<R>(null, null, LockManager.LockStatus.ACQUIRED, null, true)
  }

  override fun acquireLock(
    lockName: String,
    maximumLockDurationMillis: Long,
    onLockAcquiredCallback: Runnable
  ): LockManager.AcquireLockResponse<Void> {
    onLockAcquiredCallback.run()
    return LockManager.AcquireLockResponse<Void>(null, null, LockManager.LockStatus.ACQUIRED, null, true)
  }
}
