package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.config.LockingConfigurationProperties
import com.netflix.spinnaker.kork.lock.LockManager
import java.time.Duration

class LockingService(
  private val lockManager: LockManager,
  private val lockingConfigurationProperties: LockingConfigurationProperties
) : LockManager by lockManager {
  val swabbieMaxLockDuration: Duration
    get() = Duration.ofMillis(lockingConfigurationProperties.maximumLockDurationMillis!!)
}
