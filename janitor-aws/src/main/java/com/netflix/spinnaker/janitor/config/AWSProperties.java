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

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aws")
public class AWSProperties {
  private Boolean enabled = true;
  private List<ResourceType> resourceTypes;

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public List<ResourceType> getResourceTypes() {
    return resourceTypes;
  }

  public void setResourceTypes(List<ResourceType> resourceTypes) {
    this.resourceTypes = resourceTypes;
  }

  public static class ResourceType {
    private String name;
    private Integer retention;
    private Boolean enabled = true;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Integer getRetention() {
      return retention;
    }

    public void setRetention(Integer retention) {
      this.retention = retention;
    }

    public Boolean getEnabled() {
      return enabled;
    }

    public void setEnabled(Boolean enabled) {
      this.enabled = enabled;
    }
  }
}
