
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

package com.netflix.spinnaker.janitor.config;

import com.netflix.spinnaker.config.OkHttpClientConfiguration;
import com.netflix.spinnaker.janitor.services.internal.ClouddriverService;
import com.netflix.spinnaker.janitor.services.internal.Front50Service;
import com.netflix.spinnaker.janitor.services.internal.OrcaService;
import com.netflix.spinnaker.janitor.services.internal.TagService;
import com.netflix.spinnaker.okhttp.OkHttpClientConfigurationProperties;
import com.netflix.spinnaker.retrofit.Slf4jRetrofitLogger;
import com.squareup.okhttp.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import retrofit.RestAdapter;
import retrofit.client.OkClient;
import retrofit.converter.JacksonConverter;


import static retrofit.Endpoints.newFixedEndpoint;

@ComponentScan("com.netflix.spinnaker.janitor")
@Import(OkHttpClientConfiguration.class)
@EnableConfigurationProperties(OkHttpClientConfigurationProperties.class)
@Configuration
public class RetrofitConfig {

  @Value("${okHttpClient.connectionPool.maxIdleConnections:5}")
  private int maxIdleConnections;

  @Value("${okHttpClient.connectionPool.keepAliveDurationMs:300000}")
  private int keepAliveDurationMs;

  @Value("${okHttpClient.retryOnConnectionFailure:true}")
  private boolean retryOnConnectionFailure;

  @Autowired
  private ServiceConfiguration serviceConfiguration;

  @Bean
  OkClient retrofitClient(OkHttpClientConfiguration okHttpClientConfiguration) {
    final String userAgent = "Spinnaker-${System.getProperty('spring.application.name', 'unknown')}/${getClass().getPackage().implementationVersion ?: '1.0'}";
    OkHttpClient client = okHttpClientConfiguration.create();
    client.networkInterceptors().add(chain -> chain.proceed(
      chain.request().newBuilder().header("User-Agent", userAgent).build()
    ));

    client.setConnectionPool(new ConnectionPool(maxIdleConnections, keepAliveDurationMs));
    client.setRetryOnConnectionFailure(retryOnConnectionFailure);

    return new OkClient(client);
  }

  @Bean
  RestAdapter.LogLevel retrofitLogLevel(@Value("${retrofit.logLevel:BASIC}") String retrofitLogLevel) {
    return RestAdapter.LogLevel.valueOf(retrofitLogLevel);
  }

  @Bean
  Front50Service front50Service(OkClient retrofitClient, RestAdapter.LogLevel retrofitLogLevel) {
    return createClient("front50", retrofitClient, retrofitLogLevel, Front50Service.class);
  }

  @Bean
  ClouddriverService clouddriverService(OkClient retrofitClient, RestAdapter.LogLevel retrofitLogLevel) {
    return createClient("clouddriver", retrofitClient, retrofitLogLevel, ClouddriverService.class);
  }

  @Bean
  OrcaService orcaService(OkClient retrofitClient, RestAdapter.LogLevel retrofitLogLevel) {
    return createClient("orca", retrofitClient, retrofitLogLevel, OrcaService.class);
  }

  @Bean
  TagService tagService(OkClient retrofitClient, RestAdapter.LogLevel retrofitLogLevel) {
    return createClient("front50", retrofitClient, retrofitLogLevel, TagService.class);
  }

  private <T> T createClient(String serviceName, OkClient client, RestAdapter.LogLevel retrofitLogLevel, Class<T> serviceType) {
    ServiceConfiguration.Service service = serviceConfiguration.byName(serviceName);
    if (service == null) {
      throw new IllegalArgumentException(
        String.format("Unknown service name %s", serviceName)
      );
    }

    if (!service.getEnabled()) {
      return null;
    }

    return new RestAdapter.Builder()
      .setEndpoint(newFixedEndpoint(service.getBaseUrl()))
      .setClient(client)
      .setConverter(new JacksonConverter())
      .setLogLevel(retrofitLogLevel)
      .setLog(new Slf4jRetrofitLogger(serviceType))
      .build()
      .create(serviceType);
  }
}
