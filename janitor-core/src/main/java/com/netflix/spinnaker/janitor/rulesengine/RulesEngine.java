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

import com.netflix.spinnaker.janitor.model.Resource;
import com.netflix.spinnaker.janitor.model.Rule;

import java.util.List;
import java.util.Set;

/**
 * The interface for janitor rule engine that can decide if a resource should be a candidate of cleanup
 * based on a collection of rules.
 */

public interface RulesEngine {

  /**
   * Adds a new rule to the engine
   * @param rule a rule to apply on a resource
   * @return
   */

  RulesEngine addRule(Rule rule);

  /**
   * Fires all rules in the engine
   */

  Result run(Resource resource);

  /**
   * Gets the list of current rules
   * @return
   */

  Set<Rule> getRules();

  /**
   * Sorts rules in order they will be executed
   */

  void sortRules();

  /**
   * Adds a  list of rules
   * @param rules
   */

  void addRules(final List<Rule> rules);
}
