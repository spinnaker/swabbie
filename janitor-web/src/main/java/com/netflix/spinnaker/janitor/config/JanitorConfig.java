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

import com.netflix.spinnaker.janitor.model.Rule;
import com.netflix.spinnaker.janitor.rulesengine.BasicRulesEngine;
import com.netflix.spinnaker.janitor.rulesengine.RuleListener;
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import java.util.List;

@Configuration
@ComponentScan("com.netflix.spinnaker.janitor")
@EnableConfigurationProperties(JanitorConfigurationProperties.class)
public class JanitorConfig {
  @Bean
  public RulesEngine rulesEngine(List<Rule> rules,
                                 List<RuleListener> ruleListeners,
                                 JanitorConfigurationProperties janitorConfigurationProperties) {
    RulesEngine rulesEngine = new BasicRulesEngine(ruleListeners, janitorConfigurationProperties.getDefaultRetention(), janitorConfigurationProperties.getExcludedRules());
    rulesEngine.addRules(rules);
    return rulesEngine;
  }
}
