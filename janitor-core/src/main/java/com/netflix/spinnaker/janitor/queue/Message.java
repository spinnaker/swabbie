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

import com.netflix.spinnaker.janitor.model.Identifiable;

/**
 * Represents a Janitor message
 * A message is scoped to a cloud provider, account, resource type
 */

public abstract class Message implements Identifiable {
  private static final String PREFIX = "janitor:";

  /**
   * Helps matching a Janitor action with the current message
   * @param janitor a Janitor
   */

  abstract void match(Janitor janitor);

  /**
   * Current action (mark|cleanup)
   * This is used in the construction of the id
   * @return an action name
   */

  abstract String getAction();

  private String id;
  private String cloudProvider;
  private String account;
  private String resourceType;

  public Message(String cloudProvider, String resourceType, String account) {
    this.id = (PREFIX + getAction() + ":" + cloudProvider + ":" + account + ":" + resourceType).toLowerCase();
    this.cloudProvider = cloudProvider;
    this.account = account;
    this.resourceType = resourceType;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getCloudProvider() {
    return cloudProvider;
  }

  public void setCloudProvider(String cloudProvider) {
    this.cloudProvider = cloudProvider;
  }

  public String getAccount() {
    return account;
  }

  public void setAccount(String account) {
    this.account = account;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public Object clone() throws CloneNotSupportedException {
    return super.clone();
  }
}
