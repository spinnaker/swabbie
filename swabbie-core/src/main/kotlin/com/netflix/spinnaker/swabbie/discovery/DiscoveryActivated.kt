package com.netflix.spinnaker.swabbie.discovery

import com.netflix.appinfo.InstanceInfo
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A component that starts doing something when the instance is up in discovery
 * and stops doing that thing when it goes down.
 */
open class DiscoveryActivated : ApplicationListener<RemoteStatusChangedEvent> {
  private val up = AtomicBoolean()
  open fun onDiscoveryUpCallback(event: RemoteStatusChangedEvent) {
    up.set(true)
  }

  open fun onDiscoveryDownCallback(event: RemoteStatusChangedEvent) {
    up.set(false)
  }

  fun isUp(): Boolean {
    return up.get()
  }

  override fun onApplicationEvent(event: RemoteStatusChangedEvent) {
    if (event.source.status == InstanceInfo.InstanceStatus.UP) {
      log.info("Instance is {}... {} starting", event.source.status, javaClass.simpleName)
      onDiscoveryUpCallback(event)
    } else if (event.source.previousStatus == InstanceInfo.InstanceStatus.UP) {
      log.info("Instance is {}... {} stopping", event.source.status, javaClass.simpleName)
      onDiscoveryDownCallback(event)
    }
  }

  companion object {
    val log = LoggerFactory.getLogger(DiscoveryActivated::class.java)
  }
}
