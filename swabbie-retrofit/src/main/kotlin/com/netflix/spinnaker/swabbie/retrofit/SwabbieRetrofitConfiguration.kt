/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.retrofit

import com.jakewharton.retrofit.Ok3Client
import com.netflix.spinnaker.config.OkHttp3ClientConfiguration
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Scope
import retrofit.RestAdapter
import retrofit.client.OkClient
import java.util.concurrent.TimeUnit

@Configuration
@Import(OkHttp3ClientConfiguration::class)
@EnableConfigurationProperties
open class SwabbieRetrofitConfiguration {

  private val log = LoggerFactory.getLogger(javaClass)

  @Value("\${ok-http-client.connection-pool.max-idle-connections:5}")
  var maxIdleConnections = 5

  @Value("\${ok-http-client.connection-pool.keep-alive-duration-ms:300000}")
  var keepAliveDurationMs = 300000L

  @Value("\${ok-http-client.retry-on-connection-failure:true}")
  var retryOnConnectionFailure = true

  @Value("\${ok-http-client.spinnaker-user:swabbie@spinnaker.io}")
  var spinnakerUser = "swabbie@spinnaker.io"

  @Bean(name = arrayOf("retrofitClient", "okClient"))
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  open fun retrofitClient(
    @Qualifier("okHttp3ClientConfiguration") okHttpClientConfig: OkHttp3ClientConfiguration
  ): Ok3Client {
    val userAgent = "Spinnaker-${System.getProperty("spring.application.name", "unknown")}/" +
      (javaClass.`package`.implementationVersion ?: "1.0")
    val okHttpClient = OkHttpClient.Builder()
      .addNetworkInterceptor(
        Interceptor { chain ->
          chain.proceed(
            chain.request().newBuilder()
              .header("User-Agent", userAgent)
              .header("X-SPINNAKER-USER", spinnakerUser)
              .build()
          )
        })
      .connectionPool(ConnectionPool(maxIdleConnections, keepAliveDurationMs, TimeUnit.MILLISECONDS))
      .retryOnConnectionFailure(retryOnConnectionFailure)
      .build()
    return Ok3Client(okHttpClient)
  }

  @Bean
  open fun retrofitLogLevel(@Value("\${retrofit.log-level:BASIC}") retrofitLogLevel: String) =
    RestAdapter.LogLevel.valueOf(retrofitLogLevel)
}
