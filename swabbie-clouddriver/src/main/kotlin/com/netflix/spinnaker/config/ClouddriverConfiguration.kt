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
import com.netflix.spinnaker.okhttp.SpinnakerRequestInterceptor
import com.netflix.spinnaker.swabbie.clouddriver.CloudDriverService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import retrofit.Endpoint
import retrofit.Endpoints
import retrofit.RestAdapter
import retrofit.client.Client
import retrofit.converter.JacksonConverter

@ConditionalOnExpression("!\${eddaEndpoints.enabled:false}")
@Configuration
@Import(RetrofitConfiguration::class)
open class ClouddriverConfiguration {
  @Bean
  open fun clouddriverEndpoint(@Value("\${clouddriver.baseUrl}") clouddriverBaseUrl: String) = Endpoints.newFixedEndpoint(clouddriverBaseUrl)

  @Bean
  open fun clouddriverService(clouddriverEndpoint: Endpoint,
                              objectMapper: ObjectMapper,
                              retrofitClient: Client,
                              spinnakerRequestInterceptor: SpinnakerRequestInterceptor,
                              retrofitLogLevel: RestAdapter.LogLevel) = RestAdapter.Builder()
    .setRequestInterceptor(spinnakerRequestInterceptor)
    .setEndpoint(clouddriverEndpoint)
    .setClient(retrofitClient)
    .setLogLevel(retrofitLogLevel)
    .setConverter(JacksonConverter(objectMapper))
    .build()
    .create(CloudDriverService::class.java)
}
