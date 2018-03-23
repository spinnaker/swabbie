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

package com.netflix.spinnaker.swabbie.edda

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.EddaClient
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.ResourceProvider
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.aws.loadbalancers.AmazonElasticLoadBalancer
import com.netflix.spinnaker.swabbie.aws.securitygroups.AmazonSecurityGroup
import com.netflix.spinnaker.swabbie.model.LOAD_BALANCER
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import retrofit.RetrofitError

@Component
open class EddaAutoScalingGroupProvider(
  private val eddaClients: List<EddaClient>,
  private val retrySupport: RetrySupport,
  private val registry: Registry
) : ResourceProvider<AmazonAutoScalingGroup> {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val eddaFailureCountId: Id = registry.createId("swabbie.edda.failures")
  override fun getAll(params: Parameters): List<AmazonAutoScalingGroup>? =
    eddaServiceForRegionAndAccount(params, eddaClients).getServerGroups()

  override fun getOne(params: Parameters): AmazonAutoScalingGroup? =
    eddaServiceForRegionAndAccount(params, eddaClients).getServerGroup(params["autoScalingGroupName"] as String)

  private fun EddaService.getServerGroups(): List<AmazonAutoScalingGroup> {
    return try {
      retrySupport.retry({
        this.getAutoScalingGroups()
      }, maxRetries, retryBackOffMillis, true)
        .mapNotNull { id ->
          this.getServerGroup(id)
        }
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", SERVER_GROUP)).increment()
      log.error("failed to get server groups", e)
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
}

@Component
open class EddaAmazonElasticLoadBalancerProvider(
  private val eddaClients: List<EddaClient>,
  private val retrySupport: RetrySupport,
  private val registry: Registry
) : ResourceProvider<AmazonElasticLoadBalancer> {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val eddaFailureCountId: Id = registry.createId("swabbie.edda.failures")
  override fun getAll(params: Parameters): List<AmazonElasticLoadBalancer>? =
    eddaServiceForRegionAndAccount(params, eddaClients).getELBs()

  override fun getOne(params: Parameters): AmazonElasticLoadBalancer? =
    eddaServiceForRegionAndAccount(params, eddaClients).getELB(params["loadBalancerName"] as String)

  private fun EddaService.getELBs(): List<AmazonElasticLoadBalancer> {
    return try {
      retrySupport.retry({
        this.getLoadBalancers()
      }, maxRetries, retryBackOffMillis, true)
        .mapNotNull { id ->
          getELB(id)
        }
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
          this.getLoadBalancer(loadBalancerName)
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

@Component
open class EddaAmazonSecurityGroupProvider(
  private val eddaClients: List<EddaClient>
) : ResourceProvider<AmazonSecurityGroup> {
  override fun getAll(params: Parameters): List<AmazonSecurityGroup>? =
    eddaServiceForRegionAndAccount(params, eddaClients).let { edda ->
      edda.getSecurityGroupIds().map { edda.getSecurityGroup(it) }
    }

  override fun getOne(params: Parameters): AmazonSecurityGroup? =
    eddaServiceForRegionAndAccount(params, eddaClients).getSecurityGroup(params["groupId"] as String)
}

private fun eddaServiceForRegionAndAccount(params: Parameters, eddaClients: List<EddaClient>): EddaService = eddaClients.find {
  it.region == params["region"] && it.account == params["account"]
}.let { eddaClient ->
    if (eddaClient == null) {
      throw IllegalArgumentException("Invalid region/account specified $params")
    }
    return eddaClient.get()
  }

private const val maxRetries: Int = 3
private const val retryBackOffMillis: Long = 2000
