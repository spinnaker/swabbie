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

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.EndpointProvider
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.aws.loadbalancers.AmazonElasticLoadBalancer
import com.netflix.spinnaker.swabbie.aws.securitygroups.AmazonSecurityGroup
import com.netflix.spinnaker.swabbie.aws.snapshots.AmazonSnapshot
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.INSTANCE
import com.netflix.spinnaker.swabbie.model.LAUNCH_CONFIGURATION
import com.netflix.spinnaker.swabbie.model.LOAD_BALANCER
import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import retrofit.Endpoints
import retrofit.RequestInterceptor
import retrofit.RestAdapter
import retrofit.RetrofitError
import retrofit.client.Client
import retrofit.converter.Converter

class Edda(
  private val retrySupport: RetrySupport,
  private val registry: Registry,
  private val retrofitClient: Client,
  private val retrofitLogLevel: RestAdapter.LogLevel,
  private val spinnakerRequestInterceptor: RequestInterceptor,
  private val eddaConverter: Converter,
  accountProvider: AccountProvider,
  endpointProvider: EndpointProvider
) : AWS {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val eddaFailureCountId: Id = registry.createId("swabbie.edda.failures")
  private val clients: Map<Key, EddaService> = getClients(accountProvider, endpointProvider)

  override fun getLaunchConfigurations(params: Parameters): List<AmazonLaunchConfiguration> {
    return call(params, LAUNCH_CONFIGURATION) {
      eddaService: EddaService ->
      eddaService.getLaunchConfigs()
    } ?: emptyList()
  }

  override fun getLaunchConfiguration(params: Parameters): AmazonLaunchConfiguration? {
    return call(params, LAUNCH_CONFIGURATION) {
      eddaService: EddaService ->
      eddaService.getLaunchConfig(params.id)
    }
  }

  override fun getInstances(params: Parameters): List<AmazonInstance> {
    return call(params, INSTANCE) {
      eddaService: EddaService ->
      eddaService.getInstances()
    } ?: emptyList()
  }

  override fun getInstance(params: Parameters): AmazonInstance? {
    return call(params, INSTANCE) {
      eddaService: EddaService ->
      eddaService.getInstance(params.id)
    }
  }

  override fun getElasticLoadBalancers(params: Parameters): List<AmazonElasticLoadBalancer> {
    return call(params, LOAD_BALANCER) {
      eddaService: EddaService ->
      eddaService.getLoadBalancers()
    } ?: emptyList()
  }

  override fun getElasticLoadBalancer(params: Parameters): AmazonElasticLoadBalancer? {
    return call(params, LOAD_BALANCER) {
      eddaService: EddaService ->
      eddaService.getLoadBalancer(params.id)
    }
  }

  override fun getImages(params: Parameters): List<AmazonImage> {
    return call(params, IMAGE) {
      eddaService: EddaService ->
      eddaService.getImages()
    } ?: emptyList()
  }

  override fun getImage(params: Parameters): AmazonImage? {
    return call(params, IMAGE) {
      eddaService: EddaService ->
      eddaService.getImage(params.id)
    }
  }

  override fun getSecurityGroups(params: Parameters): List<AmazonSecurityGroup> {
    return call(params, SECURITY_GROUP) {
      eddaService: EddaService ->
      eddaService.getSecurityGroups()
    } ?: emptyList()
  }

  override fun getSecurityGroup(params: Parameters): AmazonSecurityGroup? {
    return call(params, SECURITY_GROUP) {
      eddaService: EddaService ->
      eddaService.getSecurityGroup(params.id)
    }
  }

  override fun getSnapshots(params: Parameters): List<AmazonSnapshot> {
    return call(params, SNAPSHOT) {
      eddaService: EddaService ->
      eddaService.getSnapshots()
    } ?: emptyList()
  }

  override fun getSnapshot(params: Parameters): AmazonSnapshot? {
    return call(params, SNAPSHOT) {
      eddaService: EddaService ->
      eddaService.getSnapshot(params.id)
    }
  }

  override fun getServerGroups(params: Parameters): List<AmazonAutoScalingGroup> {
    return call(params, SERVER_GROUP) {
      eddaService: EddaService ->
      eddaService.getAutoScalingGroups()
    } ?: emptyList()
  }

  override fun getServerGroup(params: Parameters): AmazonAutoScalingGroup? {
    return call(params, SERVER_GROUP) {
      eddaService: EddaService ->
      eddaService.getAutoScalingGroup(params.id)
    }
  }

  private fun <R> call(params: Parameters, resourceType: String, fx: (EddaService) -> R): R? {
    val client = clients[Key(params.account, params.region, params.environment)]
    if (client == null) {
      log.warn("No edda configured for {}/{}", params.account, params.region)
      return null
    }

    return try {
      retrySupport.retry(
        {
          try {
            AuthenticatedRequest.allowAnonymous { client.run(fx) }
          } catch (e: Exception) {
            if (e is RetrofitError && e.response.status == 404) {
              null
            } else {
              throw e
            }
          }
        },
        maxRetries, retryBackOffMillis, false
      )
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", resourceType)).increment()
      throw e
    }
  }

  data class Key(
    val accountId: String,
    val region: String,
    val environment: String
  )

  private fun getClients(accountProvider: AccountProvider, endpointProvider: EndpointProvider): Map<Key, EddaService> {
    val result: MutableMap<Key, EddaService> = mutableMapOf()
    accountProvider.getAccounts()
      .filter {
        it.eddaEnabled
      }.forEach { account ->
        account.regions!!.forEach { region ->
          result[Key(account.accountId!!, region.name, account.environment)] = eddaByRegion(account.edda!!, region.name)
        }
      }

    endpointProvider.getEndpoints()
      .filterNot { endpoint ->
        result.containsKey(Key(endpoint.accountId, endpoint.region, endpoint.environment))
      }.forEach {
        result[Key(it.accountId, it.region, it.environment)] = eddaByRegion(it.endpoint, it.region)
      }

    return result.toMap()
  }

  /**
   * The [eddaConverter] is used here to convert edda data so that it matches vanilla AWS responses.
   */
  private fun eddaByRegion(endPoint: String, region: String): EddaService {
    return RestAdapter.Builder()
      .setRequestInterceptor(spinnakerRequestInterceptor)
      .setEndpoint(Endpoints.newFixedEndpoint(endPoint.replace("{{region}}", region)))
      .setClient(retrofitClient)
      .setLogLevel(retrofitLogLevel)
      .setConverter(eddaConverter)
      .build()
      .create(EddaService::class.java)
  }
}

const val maxRetries: Int = 3
const val retryBackOffMillis: Long = 5000
