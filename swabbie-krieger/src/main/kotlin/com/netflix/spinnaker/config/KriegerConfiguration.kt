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
import com.netflix.spinnaker.swabbie.krieger.KriegerService
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

@Configuration
@Import(RetrofitConfiguration::class)
@ComponentScan("com.netflix.spinnaker.swabbie.krieger")
open class KriegerConfiguration {
  @Bean
  open fun kriegerEndpoint(@Value("\${krieger.baseUrl:none}") kriegerBaseUrl: String): Endpoint
    = Endpoints.newFixedEndpoint(kriegerBaseUrl)

  @Bean
  @ConditionalOnExpression("\${krieger.enabled:false}")
  open fun kriegerService(kriegerEndpoint: Endpoint,
                          objectMapper: ObjectMapper,
                          retrofitClient: Client,
                          spinnakerRequestInterceptor: RequestInterceptor,
                          retrofitLogLevel: RestAdapter.LogLevel): KriegerService
    = RestAdapter.Builder()
    .setRequestInterceptor(spinnakerRequestInterceptor)
    .setEndpoint(kriegerEndpoint)
    .setClient(retrofitClient)
    .setLogLevel(retrofitLogLevel)
    .setConverter(JacksonConverter(objectMapper))
    .build()
    .create(KriegerService::class.java)
}

