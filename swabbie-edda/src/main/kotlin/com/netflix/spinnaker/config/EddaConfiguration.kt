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
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.edda.EddaService
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.retrofit.SwabbieRetrofitConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import retrofit.Endpoints
import retrofit.RequestInterceptor
import retrofit.RestAdapter
import retrofit.client.Client
import retrofit.converter.JacksonConverter

@Configuration
@ComponentScan(basePackages = arrayOf(
  "com.netflix.spinnaker.swabbie.edda",
  "com.netflix.spinnaker.swabbie.aws"
))
@Import(SwabbieRetrofitConfiguration::class)
open class EddaConfiguration {
  @Bean
  open fun eddaApiClients(accountProvider: AccountProvider,
                          objectMapper: ObjectMapper,
                          retrofitClient: Client,
                          spinnakerRequestInterceptor: RequestInterceptor,
                          retrofitLogLevel: RestAdapter.LogLevel): List<EddaApiClient> {
    return accountProvider.getAccounts().filter{
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
    }.flatten()
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
