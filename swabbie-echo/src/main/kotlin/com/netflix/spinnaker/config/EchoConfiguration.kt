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
import com.netflix.spinnaker.swabbie.echo.EchoService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import retrofit.Endpoint
import retrofit.Endpoints
import retrofit.RequestInterceptor
import retrofit.RestAdapter
import retrofit.converter.JacksonConverter

@Configuration
@ComponentScan("com.netflix.spinnaker.swabbie.echo")
@Import(RetrofitConfiguration::class)
open class EchoConfiguration {
  private val log = LoggerFactory.getLogger(javaClass)

  @Bean
  open fun echoEndpoint(@Value("\${echo.base-url}") echoBaseUrl: String) = Endpoints.newFixedEndpoint(echoBaseUrl)

  @Bean
  open fun echoService(
    echoEndpoint: Endpoint,
    objectMapper: ObjectMapper,
    clientProvider: OkHttpClientProvider,
    spinnakerRequestInterceptor: RequestInterceptor,
    retrofitLogLevel: RestAdapter.LogLevel
  ) = RestAdapter.Builder()
    .setRequestInterceptor(spinnakerRequestInterceptor)
    .setEndpoint(echoEndpoint)
    .setClient(Ok3Client(clientProvider.getClient(DefaultServiceEndpoint("echo", echoEndpoint.url))))
    .setLogLevel(retrofitLogLevel)
    .setConverter(JacksonConverter(objectMapper))
    .build()
    .create(EchoService::class.java)
}
