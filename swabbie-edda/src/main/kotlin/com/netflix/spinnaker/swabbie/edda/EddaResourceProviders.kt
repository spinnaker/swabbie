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

import com.netflix.spinnaker.config.EddaClient
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.ResourceProvider
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.aws.loadbalancers.AmazonElasticLoadBalancer
import com.netflix.spinnaker.swabbie.aws.securitygroups.AmazonSecurityGroup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

//TODO: (jeyrs) handle explicit exceptions and add retries
@Component
open class EddaAutoScalingGroupProvider(
  private val eddaClients: List<EddaClient>
) : ResourceProvider<AmazonAutoScalingGroup> {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun getAll(params: Parameters): List<AmazonAutoScalingGroup>? {
    try {
      eddaServiceForRegionAndAccount(params, eddaClients).let { edda ->
        return edda.getAutoScalingGroups().mapNotNull {
          try {
            edda.getAutoScalingGroup(it)
          } catch (e: Exception) {
            log.error("failed to get server group {}", it, e)
            null
          }
        }
      }
    } catch (e: Exception) {
      log.error("failed to get server groups", e)
    }

    return emptyList()
  }

  override fun getOne(params: Parameters): AmazonAutoScalingGroup? {
    return try {
      eddaServiceForRegionAndAccount(params, eddaClients).getAutoScalingGroup(params["autoScalingGroupName"] as String)
    } catch (e: Exception) {
      log.error("failed to get server group {}", e)
      null
    }
  }
}

@Component
open class EddaAmazonElasticLoadBalancerProvider(
  private val eddaClients: List<EddaClient>
) : ResourceProvider<AmazonElasticLoadBalancer> {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun getAll(params: Parameters): List<AmazonElasticLoadBalancer>? {
    try {
      eddaServiceForRegionAndAccount(params, eddaClients).let { edda ->
        return edda.getLoadBalancers().map {
          try {
            edda.getLoadBalancer(it)
          } catch (e: Exception) {
            log.error("failed to get load balancer {}", it, e)
            return null
          }

        }
      }
    } catch (e: Exception) {
      log.error("failed to get server groups", e)
    }

    return emptyList()
  }

  override fun getOne(params: Parameters): AmazonElasticLoadBalancer? {
    return try {
      eddaServiceForRegionAndAccount(params, eddaClients).getLoadBalancer(params["loadBalancerName"] as String)
    } catch (e: Exception) {
      log.error("failed to get load balancer", e)
      return null
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
