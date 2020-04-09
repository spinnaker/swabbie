package com.netflix.spinnaker.swabbie.discovery

import com.netflix.appinfo.InstanceInfo
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener

/**
 * A component that starts doing something when the instance is up in discovery
 * and stops doing that thing when it goes down.
 */
open class DiscoveryActivated : ApplicationListener<RemoteStatusChangedEvent> {
  private val up = AtomicBoolean()
  open fun onDiscoveryUpCallback(event: RemoteStatusChangedEvent) {
    // override to get a hook on up event
  }

  open fun onDiscoveryDownCallback(event: RemoteStatusChangedEvent) {
    // override to get hook on down event
  }

  fun isUp(): Boolean {
    return up.get()
  }

  override fun onApplicationEvent(event: RemoteStatusChangedEvent) {
    if (event.source.status == InstanceInfo.InstanceStatus.UP) {
      log.info("Instance is {}... {} starting", event.source.status, javaClass.simpleName)
      up.set(true)
      onDiscoveryUpCallback(event)
    } else if (event.source.previousStatus == InstanceInfo.InstanceStatus.UP) {
      log.info("Instance is {}... {} stopping", event.source.status, javaClass.simpleName)
      up.set(false)
      onDiscoveryDownCallback(event)
    }
  }

  companion object {
    val log = LoggerFactory.getLogger(DiscoveryActivated::class.java)
  }
}
