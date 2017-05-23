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

package com.netflix.spinnaker.janitor.rulesengine;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

/**
 * Represents a result of the rules engine findings
 */

public class Result {
  private Set<Summary> summaries;
  private Boolean valid = true;

  public Result() {
    summaries = new TreeSet<>();
  }

  public Set<Summary> getSummaries() {
    return summaries;
  }
  public boolean valid() {
    return valid;
  }

  public void setSummaries(Set<Summary> summaries) {
    this.summaries = summaries;
  }

  public Boolean getValid() {
    return valid;
  }

  public void setValid(Boolean valid) {
    this.valid = valid;
  }

  public static class Summary implements Comparable<Summary> {
    private String description;
    private LocalDate terminationTime;
    public Summary(String description, LocalDate termationTime) {
      this.description = description;
      this.terminationTime = termationTime;
    }

    public String toString() {
      return String.format("%s with termination on %s ", description, terminationTime);
    }

    public boolean equals(Object obj) {
      if (obj instanceof Summary) {
        Summary that = (Summary) obj;
        return that.getDescription().equals(description) &&
          that.getTerminationTime().equals(terminationTime);
      }

      return false;
    }

    public String getDescription() {
      return description;
    }

    public LocalDate getTerminationTime() {
      return terminationTime;
    }

    @Override
    public int compareTo(Summary that) {
      if (terminationTime != null && that.getTerminationTime() != null) {
        if (terminationTime.equals(that.getTerminationTime()) && description.equalsIgnoreCase(that.getDescription())) {
          return 0;
        }
      }

      return -1;
    }
  }
}
