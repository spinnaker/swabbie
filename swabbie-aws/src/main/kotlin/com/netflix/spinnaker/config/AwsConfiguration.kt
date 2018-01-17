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
import com.netflix.spinnaker.swabbie.aws.service.EddaService
import com.netflix.spinnaker.swabbie.retrofit.SwabbieRetrofitConfiguration
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
@ComponentScan(basePackages = arrayOf(
  "com.netflix.spinnaker.swabbie.aws"
))
@Import(SwabbieRetrofitConfiguration::class)
open class AwsConfiguration {

  // TODO: jeyrs -- Edda should be added as a seperate module in swabbie-nflx. Leaving here for now to scaffold service
  @Bean open fun eddaEndpoint(): Endpoint =
    Endpoints.newFixedEndpoint("http://edda-main.us-east-1.test.netflix.net:7001")

  @Bean open fun eddaService(eddaEndpoint: Endpoint,
                             objectMapper: ObjectMapper,
                             retrofitClient: Client,
                             spinnakerRequestInterceptor: RequestInterceptor,
                             retrofitLogLevel: RestAdapter.LogLevel)
    = RestAdapter.Builder()
    .setRequestInterceptor(spinnakerRequestInterceptor)
    .setEndpoint(eddaEndpoint)
    .setClient(retrofitClient)
    .setLogLevel(retrofitLogLevel)
    .setConverter(JacksonConverter(objectMapper))
    .build()
    .create(EddaService::class.java)
}
