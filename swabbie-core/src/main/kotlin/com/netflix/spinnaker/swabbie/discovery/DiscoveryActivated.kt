package com.netflix.spinnaker.swabbie.discovery

import com.netflix.appinfo.InstanceInfo
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener

/**
 * A component that starts doing something when the instance is up in discovery
 * and stops doing that thing when it goes down.
 */
interface DiscoveryActivated : ApplicationListener<RemoteStatusChangedEvent> {

  val onDiscoveryUpCallback: (event: RemoteStatusChangedEvent) -> Unit
  val onDiscoveryDownCallback: (event: RemoteStatusChangedEvent) -> Unit

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
