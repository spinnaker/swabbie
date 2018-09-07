package com.netflix.spinnaker.swabbie.discovery

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.StatusChangeEvent
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent

import com.netflix.appinfo.InstanceInfo.InstanceStatus.OUT_OF_SERVICE
import com.netflix.appinfo.InstanceInfo.InstanceStatus.UNKNOWN
import com.netflix.appinfo.InstanceInfo.InstanceStatus.UP

class NoDiscoveryApplicationStatusPublisher(
  private val publisher: ApplicationEventPublisher
) : ApplicationListener<ContextRefreshedEvent> {
  private val log = LoggerFactory.getLogger(NoDiscoveryApplicationStatusPublisher::class.java)

  override fun onApplicationEvent(event: ContextRefreshedEvent) {
    log.warn("No discovery client is available, assuming application is up")
    setInstanceStatus(UP)
  }

  private fun setInstanceStatus(current: InstanceInfo.InstanceStatus) {
    val previous = instanceStatus
    instanceStatus = current
    publisher.publishEvent(RemoteStatusChangedEvent(StatusChangeEvent(previous, current)))
  }

  fun setInstanceEnabled(enabled: Boolean) {
    setInstanceStatus(if (enabled) UP else OUT_OF_SERVICE)
  }

  companion object {

    private var instanceStatus: InstanceInfo.InstanceStatus = UNKNOWN
  }
}
