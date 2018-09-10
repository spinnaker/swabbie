package com.netflix.spinnaker.swabbie.discovery

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.DiscoveryClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextRefreshedEvent
import java.lang.management.ManagementFactory

@Configuration
open class DiscoveryPollingConfiguration {

  @Configuration
  @ConditionalOnMissingBean(DiscoveryClient::class)
  open class NoDiscoveryConfiguration(
    @Autowired var publisher: ApplicationEventPublisher
  ) {

    @Value("\${spring.application.name:swabbie}")
    internal var appName: String? = null

    @Bean
    open fun discoveryStatusPoller(): ApplicationListener<ContextRefreshedEvent> {
      return NoDiscoveryApplicationStatusPublisher(publisher)
    }

    @Bean
    open fun currentInstanceId(): String {
      return ManagementFactory.getRuntimeMXBean().name
    }
  }

  @Configuration
  @ConditionalOnBean(DiscoveryClient::class)
  open class DiscoveryConfiguration {
    @Bean
    open fun currentInstanceId(instanceInfo: InstanceInfo): String {
      return instanceInfo.instanceId
    }
  }
}
