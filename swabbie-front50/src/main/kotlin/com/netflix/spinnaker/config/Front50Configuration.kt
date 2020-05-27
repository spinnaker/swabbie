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
import com.jakewharton.retrofit.Ok3Client
import com.netflix.spinnaker.config.okhttp3.OkHttpClientProvider
import com.netflix.spinnaker.okhttp.SpinnakerRequestInterceptor
import com.netflix.spinnaker.swabbie.front50.Front50Service
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import retrofit.Endpoint
import retrofit.Endpoints
import retrofit.RestAdapter
import retrofit.converter.JacksonConverter

@Configuration
@Import(RetrofitConfiguration::class)
@ComponentScan("com.netflix.spinnaker.swabbie.front50")
open class Front50Configuration {
  @Bean
  open fun front50Endpoint(@Value("\${front50.base-url}") front50Url: String): Endpoint = Endpoints.newFixedEndpoint(front50Url)

  @Bean
  open fun front50Service(
    front50Endpoint: Endpoint,
    objectMapper: ObjectMapper,
    clientProvider: OkHttpClientProvider,
    spinnakerRequestInterceptor: SpinnakerRequestInterceptor,
    retrofitLogLevel: RestAdapter.LogLevel
  ): Front50Service = RestAdapter.Builder()
    .setRequestInterceptor(spinnakerRequestInterceptor)
    .setEndpoint(front50Endpoint)
    .setClient(Ok3Client(clientProvider.getClient(DefaultServiceEndpoint("front50", front50Endpoint.url))))
    .setLogLevel(retrofitLogLevel)
    .setConverter(JacksonConverter(objectMapper))
    .build()
    .create(Front50Service::class.java)
}
