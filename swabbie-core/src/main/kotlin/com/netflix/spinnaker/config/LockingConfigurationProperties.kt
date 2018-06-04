package com.netflix.spinnaker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("locking")
open class LockingConfigurationProperties {
  var enabled: Boolean? = false
  var maximumLockDurationMillis: Long? = 60 * 60 * 1000L // 1hour
  var heartbeatRateMillis: Long = 6000
  var leaseDurationMillis: Long = 10000
}
