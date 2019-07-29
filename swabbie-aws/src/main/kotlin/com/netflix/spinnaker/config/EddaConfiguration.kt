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
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.EndpointProvider
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.edda.Edda
import com.netflix.spinnaker.swabbie.aws.edda.caches.EddaEndpointCache
import com.netflix.spinnaker.swabbie.aws.edda.providers.EddaEndpointProvider
import com.netflix.spinnaker.swabbie.retrofit.SwabbieRetrofitConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import retrofit.Endpoint
import retrofit.Endpoints
import retrofit.RequestInterceptor
import retrofit.RestAdapter
import retrofit.client.Client
import retrofit.converter.JacksonConverter

@ConditionalOnExpression("\${edda.enabled:false}")
@Configuration
@ComponentScan(basePackages = ["com.netflix.spinnaker.swabbie.aws"])
@Import(SwabbieRetrofitConfiguration::class)
open class EddaConfiguration {
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
  @Primary
  open fun edda(
    retrySupport: RetrySupport,
    registry: Registry,
    objectMapper: ObjectMapper,
    retrofitClient: Client,
    retrofitLogLevel: RestAdapter.LogLevel,
    spinnakerRequestInterceptor: RequestInterceptor,
    accountProvider: AccountProvider,
    endpointProvider: EndpointProvider
  ): AWS {
    return Edda(
      retrySupport,
      registry,
      objectMapper,
      retrofitClient,
      retrofitLogLevel,
      spinnakerRequestInterceptor,
      accountProvider,
      endpointProvider
    )
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
