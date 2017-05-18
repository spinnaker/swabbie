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

import java.time.LocalDate;

public class BasicResource implements Resource {
  private String id;
  private String name;
  private String resourceType;
  private Owner owner;
  private State state;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public Owner getOwner() {
    return owner;
  }

  public void setOwner(Owner owner) {
    this.owner = owner;
  }

  public State getState() {
    return state;
  }

  public void setState(State state) {
    this.state = state;
  }

  private static class Owner {
    private String name;
    private LocalDate notifiedAt;
    private Integer notificationCount = 0;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public LocalDate getNotifiedAt() {
      return notifiedAt;
    }

    public void setNotifiedAt(LocalDate notifiedAt) {
      this.notifiedAt = notifiedAt;
    }

    public Integer getNotificationCount() {
      return notificationCount;
    }

    public void setNotificationCount(Integer notificationCount) {
      this.notificationCount = notificationCount;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Owner) {
        Owner that = (Owner) o;
        return !(!name.equals(that.getName()) || !notifiedAt.equals(that.notifiedAt) || !notificationCount.equals(that.notificationCount));
      }

      return false;
    }
  }

  private static class State {
    private String name;
    private LocalDate time;
    private String description;

    enum CleanupState {
      MARKED("MARKED"),
      UNMARKED("UNMARKED"),
      OPTEDOUT("OPTEDOUT"),
      CLEANEDUP("CLEANEDUP");

      private final String value;
      CleanupState(String value) {
        this.value = value;
      }
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public LocalDate getTime() {
      return time;
    }

    public void setTime(LocalDate time) {
      this.time = time;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public boolean equals(Object o) {
      if (o instanceof State) {
        State that = (State) o;
        if (!name.equals(that.getName()) || !description.equals(that.getDescription())) {
          return false;
        }

        if (!time.equals(that.time)) {
          return false;
        }
      }

      return false;
    }
  }
}
