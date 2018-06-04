package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.config.LockingConfigurationProperties
import com.netflix.spinnaker.kork.lock.LockManager
import org.springframework.stereotype.Component
import java.time.Duration


@Component
class LockingService(
  private val lockingConfigurationProperties: LockingConfigurationProperties,
  private val lockManager: LockManager
) : LockManager by lockManager {
  val swabbieMaxLockDuration: Duration
    get() = Duration.ofSeconds(lockingConfigurationProperties.maximumLockDurationMillis!!)
}
