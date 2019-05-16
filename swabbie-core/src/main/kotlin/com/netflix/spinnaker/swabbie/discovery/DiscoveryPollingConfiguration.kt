package com.netflix.spinnaker.swabbie.discovery

import com.netflix.discovery.EurekaClient
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.lang.management.ManagementFactory
import java.util.Optional

@Configuration
open class DiscoveryPollingConfiguration {

  @Bean
  open fun currentInstanceId(applicationContext: ConfigurableApplicationContext, eurekaClientProvider: Optional<EurekaClient>): String {
    return if (eurekaClientProvider.isPresent) {
      eurekaClientProvider.get().applicationInfoManager.info.instanceId
    } else {
      applicationContext.addApplicationListener(NoDiscoveryApplicationStatusPublisher(applicationContext))
      ManagementFactory.getRuntimeMXBean().name
    }
  }
}
