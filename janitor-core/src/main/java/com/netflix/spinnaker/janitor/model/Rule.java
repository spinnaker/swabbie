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

/**
 * The rule implementing a logic to decide if a resource should be considered as a candidate of cleanup.
 */

public interface Rule extends Comparable<Rule> {

  /**
   * Getter for rule name
   * @return rule name
   */

  String getName();

  /**
   * Getter for rule description
   * @return rule description
   */

  String getDescription();

  /**
   * Determines if this rule evaluates
   * @return true if Rule evaluates and false otherwise
   * @param resource cloud resource to apply the rule on
   */

  boolean checkResource(Resource resource);

  /**
   * Checks if this rule applies
   * @param name name of the field to match
   * @return
   */

  boolean supports(String name);

  @Override
  default int compareTo(final Rule rule) {
    if (rule.getName().equalsIgnoreCase(getName()) && rule.getDescription().equalsIgnoreCase(getDescription())) {
      return 0;
    }

    return -1;
  }
}
