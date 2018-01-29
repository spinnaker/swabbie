/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.config

import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.netflix.spinnaker.swabbie.model.Configurations
import com.netflix.spinnaker.swabbie.Notifier
import com.netflix.spinnaker.swabbie.model.Notification
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.provider.AccountProvider
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.util.ClassUtils
import java.time.Clock

@Configuration
@EnableConfigurationProperties(SwabbieProperties::class)
@ComponentScan(basePackages = arrayOf("com.netflix.spinnaker.swabbie"))
open class SwabbieConfiguration {
  @Autowired
  open fun objectMapper(objectMapper: ObjectMapper) =
    objectMapper.apply {
      registerSubtypes(*findAllSubtypes(Resource::class.java, "com.netflix.spinnaker.swabbie"))
    }.registerModule(KotlinModule())
      .registerModule(JavaTimeModule())
      .disable(FAIL_ON_UNKNOWN_PROPERTIES)
      .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS)!!

  @Bean open fun clock(): Clock = Clock.systemDefaultZone()

  /**
   * Configuration namespace/id format is {cloudProvider}:{account}:{location}:{resourceType}
   * locations in aws are regions
   */
  @Bean
  open fun configuration(accountProvider: AccountProvider, properties: SwabbieProperties): Configurations<String, WorkConfiguration> {
    val globalExclusions: MutableList<Exclusion>? = properties.globalExclusions
    val configuration = Configurations<String, WorkConfiguration>()
    properties.providers.forEach { cloudProviderConfiguration ->
      cloudProviderConfiguration.resourceTypes.forEach { resourceTypeConfiguration ->
        accountProvider.getAccounts().forEach { account ->
          cloudProviderConfiguration.locations.forEach { location ->
            val configurationId = "${cloudProviderConfiguration.name}:$account:$location:${resourceTypeConfiguration.name}"
            configuration[configurationId] = WorkConfiguration(
              configurationId,
              account,
              location,
              cloudProviderConfiguration.name,
              resourceTypeConfiguration.name,
              resourceTypeConfiguration.retention,
              mergeExclusions(globalExclusions, resourceTypeConfiguration.exclusions),
              resourceTypeConfiguration.dryRun
            )
          }
        }
      }
    }

    return configuration
  }

  @Bean
  open fun taskExecutor(): ThreadPoolTaskExecutor {
    val taskExecutor = ThreadPoolTaskExecutor()
    taskExecutor.corePoolSize = 5
    return taskExecutor
  }

  // TODO: remove
  @Bean
  open fun noopNotifier(): Notifier {
    return NoopNotifier()
  }

  class NoopNotifier: Notifier {
    override fun notify(owner: String, summaries: List<Summary>): Notification {
      return Notification(0, "/dev/null", "empty")
    }
  }

  private fun findAllSubtypes(clazz: Class<*>, pkg: String): Array<Class<*>>
    = ClassPathScanningCandidateComponentProvider(false)
    .apply { addIncludeFilter(AssignableTypeFilter(clazz)) }
    .findCandidateComponents(pkg)
    .map {
      val cls = ClassUtils.resolveClassName(it.beanClassName, ClassUtils.getDefaultClassLoader())
      return@map cls
    }
    .toTypedArray()

}
