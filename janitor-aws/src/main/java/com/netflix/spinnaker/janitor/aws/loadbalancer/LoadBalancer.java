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

package com.netflix.spinnaker.janitor.aws.loadbalancer;

import com.netflix.spinnaker.janitor.Resource;
import java.util.List;


//TODO: not sure about the structure of this POJO. Probably wouldnt have to create one for each resource type
//TODO: revisit
public class LoadBalancer implements Resource {
  private String name;
  private String account;
  private String cloudProvider;
  private String region;
  private List<String> serverGroups;

  @Override
  public String getResourceType() {
    return "LoadBalancer";
  }

  @Override
  public String getId() {
    return cloudProvider + ":LoadBalancer:" + getName() + ":" + getAccount() + ":" + getRegion();
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

  public List<String> getServerGroups() {
    return serverGroups;
  }

  public void setServerGroups(List<String> serverGroups) {
    this.serverGroups = serverGroups;
  }
}
