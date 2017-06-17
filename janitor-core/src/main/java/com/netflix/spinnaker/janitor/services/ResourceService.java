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

package com.netflix.spinnaker.janitor.services;

import com.netflix.spinnaker.janitor.model.LoadBalancer;
import com.netflix.spinnaker.janitor.services.internal.ClouddriverService;
import com.netflix.spinnaker.janitor.services.internal.OrcaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResourceService {
  private final ClouddriverService clouddriverService;
  private final OrcaService orcaService;

  @Autowired
  public ResourceService(ClouddriverService clouddriverService, OrcaService orcaService) {
    this.clouddriverService = clouddriverService;
    this.orcaService = orcaService;
  }

  public List<LoadBalancer> getLoadbalancers(String account) { //TODO: hystrix this
    return clouddriverService.getLoadBalancers(account);
  }

  public void deleteLoadBalancer(String cloudProvider, String account, String region, String name) {
    Map<String, Object> operation = new HashMap<>(); //TODO: build op
    orcaService.doOperation(operation);
  }
}
