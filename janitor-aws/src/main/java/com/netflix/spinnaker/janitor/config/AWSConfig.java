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

import com.netflix.spinnaker.janitor.Rule;
import com.netflix.spinnaker.janitor.aws.loadbalancer.OrphanedLoadBalancerRule;
import com.netflix.spinnaker.janitor.rulesengine.BasicRulesEngine;
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine;
import com.netflix.spinnaker.janitor.ResourceTypes;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Optional;

@Configuration
@EnableConfigurationProperties(AWSProperties.class)
public class AWSConfig {
  private Rule orphanedLoadBalancerRule(AWSProperties awsProperties) {
    Optional<AWSProperties.ResourceType> typeOptional = awsProperties.getResourceTypes()
      .stream()
      .filter(r -> r.getName().equals(ResourceTypes.LOADBALANCER) && r.getEnabled())
      .findAny();

    if (typeOptional.isPresent()) {
      return new OrphanedLoadBalancerRule();
    }

    return null;
  }

  @Bean
  public RulesEngine awsRulesEngine(AWSProperties awsProperties) {
    RulesEngine rulesEngine = new BasicRulesEngine(Collections.emptyList());
    Rule rule1 = orphanedLoadBalancerRule(awsProperties);
    Optional.ofNullable(rule1).ifPresent(r -> rulesEngine.addRule(rule1));
    return rulesEngine;
  }
}
