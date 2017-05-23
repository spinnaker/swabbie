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

package com.netflix.spinnaker.janitor.aws.model;

import com.netflix.spinnaker.janitor.model.LoadBalancer;

import java.util.List;

public class AmazonLoadBalancer implements LoadBalancer {
  private String name;
  private String account;
  private String cloudProvider = "aws";
  private String region;
  private List<LoadBalancer.LoadBalancerServerGroup> serverGroups;

  @Override
  public String getId() {
    return cloudProvider + ":loadbalancer:" + name + ":" + account + ":" + region;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getAccount() {
    return account;
  }

  public void setAccount(String account) {
    this.account = account;
  }

  public String getCloudProvider() {
    return cloudProvider;
  }

  public void setCloudProvider(String cloudProvider) {
    this.cloudProvider = cloudProvider;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public List<LoadBalancer.LoadBalancerServerGroup> getServerGroups() {
    return serverGroups;
  }

  public void setServerGroups(List<LoadBalancer.LoadBalancerServerGroup> serverGroups) {
    this.serverGroups = serverGroups;
  }

  public boolean equals(Object obj) {
    if (obj instanceof AmazonLoadBalancer) {
      AmazonLoadBalancer that = (AmazonLoadBalancer) obj;
      return that.getAccount().equals(account)
        && that.getName().equals(name)
        && that.getCloudProvider().equals(cloudProvider)
        && that.getRegion().equals(region);
    }

    return false;
  }
}
