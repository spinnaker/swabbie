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

package com.netflix.spinnaker.janitor.queue;

/**
 * A message with resource cleanup semantics
 * Each message contains contextual data on the resource to cleanup. (resource ID, name and region)
 */

public class CleanupMessage extends Message {
  private String resourceId;
  private String resourceName;
  private String region;

  public CleanupMessage(String cloudProvider, String resourceType, String account, String resourceId, String resourceName, String region) {
    super(cloudProvider, resourceType, account);
    this.resourceId = resourceId;
    this.resourceName = resourceName;
    this.region = region;
  }

  @Override
  void match(Janitor visitor) {
    visitor.cleanup(this);
  }

  @Override
  String getAction() {
    return "cleanup";
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getResourceName() {
    return resourceName;
  }

  public String getRegion() {
    return this.region;
  }
}
