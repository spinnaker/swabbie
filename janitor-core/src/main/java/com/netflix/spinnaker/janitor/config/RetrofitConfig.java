
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

import com.netflix.spinnaker.janitor.services.internal.ClouddriverService;
import com.netflix.spinnaker.janitor.services.internal.Front50Service;
import com.netflix.spinnaker.janitor.services.internal.OrcaService;
import com.netflix.spinnaker.janitor.services.internal.TagService;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.util.concurrent.TimeUnit;

@ComponentScan("com.netflix.spinnaker.janitor")
@Configuration
public class RetrofitConfig {
  @Autowired
  private ServiceConfiguration serviceConfiguration;

  @Bean
  OkHttpClient retrofitClient() {
    //TODO: update this, might need to downgrade retrofit to use the default or
    return new OkHttpClient.Builder()
      .readTimeout(30000, TimeUnit.MILLISECONDS)
      .connectTimeout(30000, TimeUnit.MILLISECONDS)
      .build();
  }

  @Bean
  Front50Service front50Service(OkHttpClient retrofitClient) {
    return createClient("front50", retrofitClient,  Front50Service.class);
  }

  @Bean
  ClouddriverService clouddriverService(OkHttpClient retrofitClient) {
    return createClient("clouddriver", retrofitClient,  ClouddriverService.class);
  }

  @Bean
  OrcaService orcaService(OkHttpClient retrofitClient) {
    return createClient("orca", retrofitClient,  OrcaService.class);
  }

  @Bean
  TagService tagService(OkHttpClient retrofitClient) {
    return createClient("orca", retrofitClient,  TagService.class);
  }

  <T> T createClient(String serviceName, OkHttpClient okHttpClient, Class<T> clazz) {
    ServiceConfiguration.Service service = serviceConfiguration.byName(serviceName);
    Retrofit retrofit = new Retrofit.Builder()
      .baseUrl(service.getBaseUrl())
      .addConverterFactory(JacksonConverterFactory.create())
      .client(okHttpClient)
      .build();

    return retrofit.create(clazz);
  }
}
