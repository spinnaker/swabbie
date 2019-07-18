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

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.swabbie.aws.edda.EddaEndpointsService
import com.netflix.spinnaker.swabbie.aws.edda.EddaService
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.EndpointProvider
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.edda.Edda
import com.netflix.spinnaker.swabbie.aws.edda.caches.EddaEndpointCache
import com.netflix.spinnaker.swabbie.aws.edda.providers.EddaEndpointProvider

import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.model.Region
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.retrofit.SwabbieRetrofitConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import retrofit.Endpoint
import retrofit.Endpoints
import retrofit.RequestInterceptor
import retrofit.RestAdapter
import retrofit.client.Client
import retrofit.converter.JacksonConverter

@ConditionalOnExpression("\${edda.enabled:true}")
@Configuration
@ComponentScan(basePackages = ["com.netflix.spinnaker.swabbie.aws"])
@Import(SwabbieRetrofitConfiguration::class)
open class EddaConfiguration {
  @Bean
  open fun eddaApiClients(
    accountProvider: AccountProvider,
    endpointProvider: EndpointProvider,
    objectMapper: ObjectMapper,
    retrofitClient: Client,
    spinnakerRequestInterceptor: RequestInterceptor,
    retrofitLogLevel: RestAdapter.LogLevel
  ): List<EddaApiClient> {
    val accountEddas: List<EddaApiClient> = accountProvider.getAccounts()
      .asSequence()
      .filter {
        it.eddaEnabled
      }.map { account ->
        account.regions!!.map { region ->
          EddaApiClient(
            region.name,
            account,
            objectMapper,
            retrofitClient,
            spinnakerRequestInterceptor,
            retrofitLogLevel
          )
        }
      }.toList()
      .flatten()

    val endpointEddas: List<EddaApiClient> = endpointProvider.getEndpoints()
      .asSequence()
      .filter { endpoint ->
        accountEddas.none {
          endpoint.region == it.region &&
            endpoint.accountId == it.account.accountId &&
            endpoint.environment == it.account.environment
        }
      }.map {
        EddaApiClient(
          it.region,
          SpinnakerAccount(
            true,
            it.accountId,
            "aws",
            it.name,
            it.endpoint,
            listOf(Region(false, it.region)),
            it.environment
          ),
          objectMapper,
          retrofitClient,
          spinnakerRequestInterceptor,
          retrofitLogLevel
        )
      }.toList()

    return listOf(accountEddas, endpointEddas).flatten()
  }

  @Bean
  open fun eddaEndpointCache(eddaEndpointsService: EddaEndpointsService): EddaEndpointCache {
    return EddaEndpointCache(eddaEndpointsService)
  }

  @Bean
  open fun eddaEndpointProvider(eddaEndpointCache: EddaEndpointCache): EndpointProvider {
    return EddaEndpointProvider(eddaEndpointCache)
  }

  @Bean
  open fun eddaEndpointsEndpoint(@Value("\${edda-endpoints.base-url}") eddaEndpointsBaseUrl: String): Endpoint {
    return Endpoints.newFixedEndpoint(eddaEndpointsBaseUrl)!!
  }

  @Bean
  open fun aws(eddaApiClients: List<EddaApiClient>, retrySupport: RetrySupport, registry: Registry): AWS {
    return Edda(eddaApiClients, retrySupport, registry)
  }

  @Bean
  open fun eddaEndpointsService(
    eddaEndpointsEndpoint: Endpoint,
    objectMapper: ObjectMapper,
    retrofitClient: Client,
    spinnakerRequestInterceptor: RequestInterceptor,
    retrofitLogLevel: RestAdapter.LogLevel
  ): EddaEndpointsService {
    return RestAdapter.Builder()
      .setRequestInterceptor(spinnakerRequestInterceptor)
      .setEndpoint(eddaEndpointsEndpoint)
      .setClient(retrofitClient)
      .setLogLevel(retrofitLogLevel)
      .setConverter(JacksonConverter(objectMapper))
      .build()
      .create(EddaEndpointsService::class.java)
  }
}

data class EddaApiClient(
  val region: String,
  val account: Account,
  private val objectMapper: ObjectMapper,
  private val retrofitClient: Client,
  private val spinnakerRequestInterceptor: RequestInterceptor,
  private val retrofitLogLevel: RestAdapter.LogLevel
) {
  fun get(): EddaService =
    RestAdapter.Builder()
      .setRequestInterceptor(spinnakerRequestInterceptor)
      .setEndpoint(Endpoints.newFixedEndpoint(account.edda!!.replace("{{region}}", region)))
      .setClient(retrofitClient)
      .setLogLevel(retrofitLogLevel)
      .setConverter(JacksonConverter(objectMapper))
      .build()
      .create(EddaService::class.java)
}
