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

import com.netflix.spinnaker.janitor.Resource;
import com.netflix.spinnaker.janitor.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.List;
import java.util.TreeSet;

public class BasicRulesEngine implements RulesEngine {
  private static final Logger LOGGER = LoggerFactory.getLogger(BasicRulesEngine.class);
  private List<RuleListener> listeners;
  private Set<Rule> rules;

  public BasicRulesEngine(List<RuleListener> listeners) {
    this.rules = new TreeSet<>();
    this.listeners = listeners;
  }

  @Override
  public BasicRulesEngine addRule(Rule rule) {
    rules.add(rule);
    return this;
  }

  @Override
  public void run(Resource resource) {
    sort();
    applyAll(resource);
  }

  private void applyAll(Resource resource) {
    for (Rule rule : rules) {
      if (rule.checkResource(resource)) {
        LOGGER.info("rule {} evaluated", rule.getName());
        triggerListenersOnEvaluated(rule, resource);
      } else {
        LOGGER.info("rule {} did not evaluate", rule.getName());
        triggerListenersOnNotEvaluated(rule, resource);
      }
    }

    LOGGER.info("completed run for resource {}", resource.getId());
    triggerListenersOnCompleted(resource);
  }

  private void triggerListenersOnEvaluated(Rule rule, Resource resource) {
    for (RuleListener listener : listeners ) {
      listener.onRuleEvaluated(rule, resource);
    }
  }

  private void triggerListenersOnNotEvaluated(Rule rule, Resource resource) {
    for (RuleListener listener : listeners ) {
      listener.onRuleNotEvaluated(rule, resource);
    }
  }

  private void triggerListenersOnCompleted(Resource resource) {
    for (RuleListener listener : listeners ) {
      listener.onComplete(resource);
    }
  }

  private void sort() {
    this.rules = new TreeSet<>(rules);
  }

  @Override
  public Set<Rule> getRules() {
    return rules;
  }

  @Override
  public void sortRules() {
    sort();
  }
}
