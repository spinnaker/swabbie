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

package com.netflix.spinnaker.janitor.aws.rules;

import com.netflix.spinnaker.janitor.aws.model.AmazonLoadBalancer;
import com.netflix.spinnaker.janitor.model.LoadBalancer;
import com.netflix.spinnaker.janitor.model.Resource;
import com.netflix.spinnaker.janitor.model.Rule;
import com.netflix.spinnaker.janitor.model.ResourceTypes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@ConditionalOnExpression("${aws.LoadBalancer.enabled:true}")
@Component
public class OrphanedLoadBalancerRule implements Rule {
  private final String NAME = "Orphaned Load Balancer";
  private final String DESCRIPTION = "Load balancer not referenced by any Server Group";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return DESCRIPTION;
  }

  @Override
  public boolean checkResource(Resource resource) {
    if (resource instanceof AmazonLoadBalancer) {
      LoadBalancer loadBalancer = (LoadBalancer) resource;
      return loadBalancer.getServerGroups().isEmpty();
    }

    return false;
  }

  @Override
  public boolean supports(String type) {
    return ResourceTypes.LOADBALANCER.equalsIgnoreCase(type);
  }
}
