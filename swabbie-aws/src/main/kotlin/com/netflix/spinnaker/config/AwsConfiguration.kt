/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.config

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.kork.aws.bastion.BastionConfig
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.CachedViewProvider
import com.netflix.spinnaker.swabbie.InMemorySingletonCache
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Vanilla
import com.netflix.spinnaker.swabbie.aws.caches.AmazonImagesUsedByInstancesCache
import com.netflix.spinnaker.swabbie.aws.caches.ImagesUsedByInstancesProvider
import com.netflix.spinnaker.swabbie.aws.caches.AmazonLaunchConfigurationCache
import com.netflix.spinnaker.swabbie.aws.caches.AmazonLaunchConfigurationInMemoryCache
import com.netflix.spinnaker.swabbie.aws.caches.LaunchConfigurationCacheProvider
import com.netflix.spinnaker.swabbie.aws.caches.AmazonImagesUsedByInstancesInMemoryCache
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.aws.images.AmazonImageHandler
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.rmi.registry.Registry
import java.time.Clock

@Configuration
@ComponentScan(basePackages = ["com.netflix.spinnaker.swabbie.aws"])
@Import(BastionConfig::class)
open class AwsConfiguration {
  private val defaultRegion = "us-west-2" // TODO: (Jeyrs) Make configurable

  @Bean
  open fun imagesUsedByInstancesProvider(
    clock: Clock,
    workConfigurations: List<WorkConfiguration>,
    accountProvider: AccountProvider,
    aws: AWS
  ): CachedViewProvider<AmazonImagesUsedByInstancesCache>? {
    if (workConfigurations.none { it.resourceType == IMAGE }) {
      return null
    }

    return ImagesUsedByInstancesProvider(clock, accountProvider, aws)
  }

  @Bean
  open fun launchConfigurationCacheProvider(
    clock: Clock,
    workConfigurations: List<WorkConfiguration>,
    accountProvider: AccountProvider,
    aws: AWS
  ): CachedViewProvider<AmazonLaunchConfigurationCache>? {
    if (workConfigurations.none { it.resourceType == IMAGE }) {
      return null
    }

    return LaunchConfigurationCacheProvider(clock, workConfigurations, accountProvider, aws)
  }

  @Bean
  @ConditionalOnBean(LaunchConfigurationCacheProvider::class)
  open fun launchConfigurationInMemoryCache(
    provider: CachedViewProvider<AmazonLaunchConfigurationCache>
  ): AmazonLaunchConfigurationInMemoryCache {
    return AmazonLaunchConfigurationInMemoryCache(provider)
  }

  @Bean
  @ConditionalOnBean(ImagesUsedByInstancesProvider::class)
  open fun imagesUsedByInstancesInMemoryCache(
    provider: CachedViewProvider<AmazonImagesUsedByInstancesCache>
  ): AmazonImagesUsedByInstancesInMemoryCache {
    return AmazonImagesUsedByInstancesInMemoryCache(provider)
  }

  @Bean
  open fun sts(awsCredentialsProvider: AWSCredentialsProvider): AWSSecurityTokenService? {
    return AWSSecurityTokenServiceClient
      .builder()
      .withCredentials(awsCredentialsProvider)
      .withRegion(defaultRegion)
      .build()
  }

  @Bean
  open fun aws(
    sts: AWSSecurityTokenService,
    objectMapper: ObjectMapper,
    accountProvider: AccountProvider
  ): AWS {
    return Vanilla(sts, objectMapper, accountProvider)
  }

  @Bean
  @ConditionalOnBean(
    value = [AmazonImagesUsedByInstancesInMemoryCache::class, AmazonLaunchConfigurationInMemoryCache::class]
  )
  open fun amazonImageHandler(
    registry: com.netflix.spectator.api.Registry,
    clock: Clock,
    notifiers: List<Notifier>,
    resourceTrackingRepository: ResourceTrackingRepository,
    resourceStateRepository: ResourceStateRepository,
    resourceOwnerResolver: ResourceOwnerResolver<AmazonImage>,
    exclusionPolicies: List<ResourceExclusionPolicy>,
    applicationEventPublisher: ApplicationEventPublisher,
    retrySupport: RetrySupport,
    dynamicConfigService: DynamicConfigService,
    launchConfigurationCache: InMemorySingletonCache<AmazonLaunchConfigurationCache>,
    imagesUsedByinstancesCache: InMemorySingletonCache<AmazonImagesUsedByInstancesCache>,
    rules: List<Rule<AmazonImage>>,
    aws: AWS,
    orcaService: OrcaService,
    applicationUtils: ApplicationUtils,
    taskTrackingRepository: TaskTrackingRepository,
    resourceUseTrackingRepository: ResourceUseTrackingRepository,
    usedResourceRepository: UsedResourceRepository,
    swabbieProperties: SwabbieProperties
  ): AmazonImageHandler{
    return AmazonImageHandler(
      registry,
      clock,
      notifiers,
      resourceTrackingRepository,
      resourceStateRepository,
      resourceOwnerResolver,
      exclusionPolicies,
      applicationEventPublisher,
      retrySupport,
      dynamicConfigService,
      launchConfigurationCache,
      imagesUsedByinstancesCache,
      rules,
      aws,
      orcaService,
      applicationUtils,
      taskTrackingRepository,
      resourceUseTrackingRepository,
      usedResourceRepository,
      swabbieProperties
    )
  }
}
