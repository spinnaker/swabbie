/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.aws.edda

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.EddaApiClient
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.aws.edda.providers.EddaApiSupport
import com.netflix.spinnaker.swabbie.aws.edda.providers.maxRetries
import com.netflix.spinnaker.swabbie.aws.edda.providers.retryBackOffMillis
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.aws.loadbalancers.AmazonElasticLoadBalancer
import com.netflix.spinnaker.swabbie.aws.securitygroups.AmazonSecurityGroup
import com.netflix.spinnaker.swabbie.aws.snapshots.AmazonSnapshot
import com.netflix.spinnaker.swabbie.model.LAUNCH_CONFIGURATION
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import com.netflix.spinnaker.swabbie.model.INSTANCE
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.LOAD_BALANCER
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import retrofit.RetrofitError

class Edda(
  eddaApiClients: List<EddaApiClient>,
  private val retrySupport: RetrySupport,
  private val registry: Registry
) : AWS, EddaApiSupport(eddaApiClients, registry) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  override fun getLaunchConfigurations(params: Parameters): List<AmazonLaunchConfiguration> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getLaunchConfigurations()
    }

    return emptyList()
  }

  override fun getLaunchConfiguration(params: Parameters): AmazonLaunchConfiguration? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getLaunchConfiguration(params.id)
    }

    return null
  }

  private fun EddaService.getLaunchConfiguration(launchConfigurationName: String): AmazonLaunchConfiguration? {
    return try {
      retrySupport.retry({
        try {
          AuthenticatedRequest.allowAnonymous { this.getLaunchConfig(launchConfigurationName) }
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", LAUNCH_CONFIGURATION)).increment()
      log.error("failed to get launch config {}", launchConfigurationName, e)
      throw e
    }
  }

  private fun EddaService.getLaunchConfigurations(): List<AmazonLaunchConfiguration> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getLaunchConfigs() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", LAUNCH_CONFIGURATION)).increment()
      log.error("failed to get instances", e)
      throw e
    }
  }

  override fun getInstances(params: Parameters): List<AmazonInstance> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getNonTerminatedInstances()
    }

    return emptyList()
  }

  override fun getInstance(params: Parameters): AmazonInstance? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getSingleInstance(params.id)
    }

    return null
  }

  override fun getElasticLoadBalancers(params: Parameters): List<AmazonElasticLoadBalancer> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getELBs()
    }

    return emptyList()
  }

  override fun getElasticLoadBalancer(params: Parameters): AmazonElasticLoadBalancer? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getELB(params.id)
    }

    return null
  }

  override fun getImages(params: Parameters): List<AmazonImage> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getAmis()
    }

    return emptyList()
  }

  override fun getImage(params: Parameters): AmazonImage? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getAmi(params.id)
    }

    return null
  }

  override fun getSecurityGroups(params: Parameters): List<AmazonSecurityGroup> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return AuthenticatedRequest.allowAnonymous { getSecurityGroups() }
    }

    return emptyList()
  }

  override fun getSecurityGroup(params: Parameters): AmazonSecurityGroup? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return AuthenticatedRequest.allowAnonymous { getSecurityGroup(params.id) }
    }

    return null
  }

  override fun getSnapshots(params: Parameters): List<AmazonSnapshot> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getEbsSnapshots()
    }

    return emptyList()
  }

  override fun getSnapshot(params: Parameters): AmazonSnapshot? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getEbsSnapshot(params.id)
    }

    return null
  }

  override fun getServerGroups(params: Parameters): List<AmazonAutoScalingGroup> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return AuthenticatedRequest.allowAnonymous { getServerGroups() }
    }

    return emptyList()
  }

  override fun getServerGroup(params: Parameters): AmazonAutoScalingGroup? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return AuthenticatedRequest.allowAnonymous { getServerGroup(params.id) }
    }

    return null
  }

  private fun EddaService.getServerGroups(): List<AmazonAutoScalingGroup> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getAutoScalingGroups() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", SERVER_GROUP)).increment()
      log.error("failed to get snapshots", e)
      throw e
    }
  }

  private fun EddaService.getServerGroup(autoScalingGroupName: String): AmazonAutoScalingGroup? {
    try {
      return retrySupport.retry({
        try {
          this.getAutoScalingGroup(autoScalingGroupName)
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", SERVER_GROUP)).increment()
      log.error("failed to get server group {}", autoScalingGroupName, e)
      throw e
    }
  }

  private fun EddaService.getEbsSnapshot(snapshotId: String): AmazonSnapshot? {
    return try {
      retrySupport.retry({
        try {
          AuthenticatedRequest.allowAnonymous { this.getSnapshot(snapshotId) }
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", SNAPSHOT)).increment()
      log.error("failed to get snapshot {}", snapshotId, e)
      throw e
    }
  }

  private fun EddaService.getEbsSnapshots(): List<AmazonSnapshot> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getSnapshots() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", SNAPSHOT)).increment()
      log.error("failed to get snapshots", e)
      throw e
    }
  }

  private fun EddaService.getAmis(): List<AmazonImage> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getImages() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", IMAGE)).increment()
      log.error("failed to get images", e)
      throw e
    }
  }

  private fun EddaService.getAmi(imageId: String): AmazonImage? {
    return try {
      retrySupport.retry({
        try {
          AuthenticatedRequest.allowAnonymous { this.getImage(imageId) }
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", IMAGE)).increment()
      log.error("failed to get image {}", imageId, e)
      throw e
    }
  }

  private fun EddaService.getSingleInstance(instanceId: String): AmazonInstance? {
    return try {
      retrySupport.retry({
        try {
          AuthenticatedRequest.allowAnonymous { this.getInstance(instanceId) }
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(
        eddaFailureCountId.withTags("resourceType", INSTANCE)).increment()
      log.error("failed to get instance {}", instanceId, e)
      throw e
    }
  }

  private fun EddaService.getNonTerminatedInstances(): List<AmazonInstance> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getInstances() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", INSTANCE)).increment()
      log.error("failed to get instances", e)
      throw e
    }
  }

  private fun EddaService.getELBs(): List<AmazonElasticLoadBalancer> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getLoadBalancers() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", LOAD_BALANCER)).increment()
      log.error("failed to get load balancers", e)
      throw e
    }
  }

  private fun EddaService.getELB(loadBalancerName: String): AmazonElasticLoadBalancer? {
    return try {
      retrySupport.retry({
        try {
          AuthenticatedRequest.allowAnonymous { this.getLoadBalancer(loadBalancerName) }
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", LOAD_BALANCER)).increment()
      log.error("failed to get load balancer {}", loadBalancerName, e)
      throw e
    }
  }
}
