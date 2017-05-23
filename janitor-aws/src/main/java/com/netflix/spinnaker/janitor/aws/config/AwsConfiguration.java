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

package com.netflix.spinnaker.janitor.aws.config;

import com.netflix.spinnaker.janitor.model.ResourceTypes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.*;


@Configuration
@ConditionalOnExpression("${aws.enabled:false}")
@ComponentScan("com.netflix.spinnaker.janitor.aws")
@EnableConfigurationProperties(AwsConfigurationProperties.class)
public class AwsConfiguration {
  @Bean
  Map<String, Integer> resourceTypeToRetentionDays(AwsConfigurationProperties awsConfigurationProperties) {
    Map<String, Integer> result = new HashMap<>();
    result.put(ResourceTypes.LOADBALANCER, awsConfigurationProperties.getLoadBalancerRetentionDays());//TODO: add other resource types
    return result;
  }
}
