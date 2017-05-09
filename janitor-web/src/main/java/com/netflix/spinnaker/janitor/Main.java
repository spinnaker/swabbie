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

package com.netflix.spinnaker.janitor;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.web.SpringBootServletInitializer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Import({ WebConfig.class })
@ComponentScan({ "com.netflix.spinnaker.config"})
@EnableAutoConfiguration
public class Main extends SpringBootServletInitializer {
  private static final Map<String, Object> DEFAULT_PROPS = new HashMap<String, Object>() {
    {
      put("netflix.environment", "test");
      put("netflix.account", "${netflix.environment}");
      put("netflix.stack", "test");
      put("spring.config.location", "${user.home}/.spinnaker/");
      put("spring.application.name", "janitor");
      put("spring.config.name", "spinnaker,${spring.application.name}");
      put("spring.profiles.active", "${netflix.environment},local");
    }
  };

  static void main(String[] args) {
    new SpringApplicationBuilder().properties(DEFAULT_PROPS).sources(Main.class).run(args);
  }

  @Override
  public SpringApplicationBuilder configure(SpringApplicationBuilder application) {
    return application.properties(DEFAULT_PROPS).sources(Main.class);
  }
}
