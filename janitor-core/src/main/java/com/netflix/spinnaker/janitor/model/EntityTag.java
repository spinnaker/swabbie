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

package com.netflix.spinnaker.janitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * An augmented entity tag used by Janitor to mark resources
 */

public class EntityTag {
  private String valueType = "object";
  private String name;
  private String namespace;
  private Value value = new Value();

  public void setValueType(String valueType) {
    this.valueType = valueType;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setValue(Value value) {
    this.value = value;
  }

  public Value getValue() {
    return value;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public String getName() {
    return name;
  }

  public EntityTag withName(String name) {
    this.name = name;
    return this;
  }

  @JsonIgnore
  public EntityTag setMarked(String description, LocalDate time) {
    this.value.markWithReason(description, time);
    return this;
  }

  @JsonIgnore
  public EntityTag setScheduledTerminationAt(LocalDate scheduledTerminationAt) {
    this.value.scheduledTerminationAt = scheduledTerminationAt;
    return this;
  }

  @JsonIgnore
  private ResourceState.CleanupState getResourceState() {
    return this.value.getResourceState().getCleanupState();
  }

  @JsonIgnore
  public boolean getOptedOut() {
    return getResourceState() == ResourceState.CleanupState.OPTEDOUT;
  }

  @JsonIgnore
  public boolean getMarked() {
    return getResourceState() == ResourceState.CleanupState.MARKED;
  }

  @JsonIgnore
  public boolean getTerminated() {
    return getResourceState() == ResourceState.CleanupState.TERMINATED;
  }

  @JsonIgnore
  public LocalDate getScheduledTerminationAt() {
    return this.value.getScheduledTerminationAt();
  }

  @JsonIgnore
  public void setUnmarked(LocalDate time) {
    this.value.unmarkAt(time);
    this.value.scheduledTerminationAt = null;
  }

  @JsonIgnore
  public void setOptedout(LocalDate time) {
    this.value.optedOutAt(time);
    this.value.scheduledTerminationAt = null;
  }

  @JsonIgnore
  public void setTerminatedAt(LocalDate terminatedAt) {
    this.value.resourceState.cleanupState = ResourceState.CleanupState.TERMINATED;
    this.value.resourceState.time = terminatedAt;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Value  {
    private String type = "alert";
    private Notification notification;
    private LocalDate scheduledTerminationAt;
    private ResourceState resourceState = new ResourceState(ResourceState.CleanupState.UNMARKED, null, null);

    public Notification getNotification() {
      return notification;
    }

    public LocalDate getScheduledTerminationAt() {
      return scheduledTerminationAt;
    }

    public ResourceState getResourceState() {
      return this.resourceState;
    }

    @JsonIgnore
    public Value withNotification(Notification notification) {
      this.notification = notification;
      return this;
    }

    @JsonIgnore
    public void markWithReason(String terminationReason, LocalDate markedAt) {
      this.resourceState.setTerminationReason(terminationReason);
      this.resourceState.setCleanupState(ResourceState.CleanupState.MARKED);
      this.resourceState.setTime(markedAt);
    }

    @JsonIgnore
    public void unmarkAt(LocalDate unmarkedAt) {
      this.resourceState.setCleanupState(ResourceState.CleanupState.UNMARKED);
      this.resourceState.setTime(unmarkedAt);
    }

    @JsonIgnore
    public void optedOutAt(LocalDate time) {
      this.resourceState.setCleanupState(ResourceState.CleanupState.OPTEDOUT);
      this.resourceState.setTime(time);
    }
  }

  /**
   * Represents the state of a resource
   */

  public static class ResourceState {
    /**
     * The cleanup state
     */

    private CleanupState cleanupState;

    /**
     * The time the state got applied
     */

    private LocalDate time;

    /**
     * The description or reason for the said state
     */

    private String terminationReason;

    public String getTerminationReason() {
      return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
      this.terminationReason = terminationReason;
    }

    public CleanupState getCleanupState() {
      return cleanupState;
    }

    public void setCleanupState(CleanupState cleanupState) {
      this.cleanupState = cleanupState;
    }

    public LocalDate getTime() {
      return time;
    }

    public void setTime(LocalDate time) {
      this.time = time;
    }

    public ResourceState(CleanupState cleanupState, LocalDate time, String terminationReason) {
      this.cleanupState = cleanupState;
      this.time = time;
      this.terminationReason = terminationReason;
    }

    public enum CleanupState {
      MARKED,
      UNMARKED,
      OPTEDOUT,
      TERMINATED
    }
  }

  /**
   * An object representing a notification
   */

  static class Notification {
    /**
     * How many times a user has been notified
     */

    private int count;

    /**
     * The resource owner's email address or username
     */

    private String owner;

    /**
     * Notification means. i.e slack, email
     */

    private String channel;

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }

    public String getOwner() {
      return owner;
    }

    public void setOwner(String owner) {
      this.owner = owner;
    }

    public String getChannel() {
      return channel;
    }

    public void setChannel(String channel) {
      this.channel = channel;
    }
  }
}
