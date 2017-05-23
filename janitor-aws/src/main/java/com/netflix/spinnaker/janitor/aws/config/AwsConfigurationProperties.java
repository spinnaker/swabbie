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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties("aws")
public class AwsConfigurationProperties {
  @NestedConfigurationProperty
  private LoadBalancer loadBalancer;

  public LoadBalancer getLoadBalancer() {
    return loadBalancer;
  }

  public void setLoadBalancer(LoadBalancer loadBalancer) {
    this.loadBalancer = loadBalancer;
  }

  public Integer getLoadBalancerRetentionDays() {
    return this.loadBalancer.getRetentionDays();
  }

  public static class ResourceTypeConfig {
    private Integer retentionDays;
    private Boolean enabled = true;

    public Integer getRetentionDays() {
      return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
      this.retentionDays = retentionDays;
    }

    public Boolean getEnabled() {
      return enabled;
    }

    public void setEnabled(Boolean enabled) {
      this.enabled = enabled;
    }
  }

  public static class LoadBalancer extends ResourceTypeConfig {}
}
