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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A basic Rules Engine
 */

public class BasicRulesEngine implements RulesEngine {
  private static final Logger LOGGER = LoggerFactory.getLogger(BasicRulesEngine.class);
  private List<RuleListener> ruleListeners;
  private Set<Rule> rules;
  private Set<String> excludedRules;
  private int defaultRetentionDays;

  public BasicRulesEngine(List<RuleListener> ruleListeners, int defaultRetention, Set<String> excludedRules) {
    this.rules = new TreeSet<>();
    this.ruleListeners = ruleListeners;
    this.defaultRetentionDays = defaultRetention;
    this.excludedRules = excludedRules;
  }

  @Override
  public BasicRulesEngine addRule(Rule rule) {
    rules.add(rule);
    return this;
  }

  @Override
  public Result run(Resource resource) {
    sort();
    filterDisabledRules();
    Result result = new Result();
    applyAll(resource, result);
    return result;
  }

  private void filterDisabledRules() {
    rules = rules
      .stream()
      .filter(r -> !excludedRules.contains(r.getName()))
      .collect(Collectors.toSet());
  }

  private void applyAll(Resource resource, Result result) {
    for (Rule rule : rules) {
      if (rule.supports(resource.getResourceType()) && rule.checkResource(resource)) {
        LocalDate terminationDate = LocalDate.now().plusDays(defaultRetentionDays);
        result.getSummaries().add(
          new Result.Summary(rule.getDescription(), terminationDate)
        );

        result.setValid(false);
        triggerListenersOnEvaluated(rule, resource);
      } else {
        triggerListenersOnNotEvaluated(rule, resource);
      }
    }

    LOGGER.info("completed run for resource {}", resource);
    triggerListenersOnCompleted(resource);
  }

  private void triggerListenersOnEvaluated(Rule rule, Resource resource) {
    Optional.ofNullable(ruleListeners).ifPresent( list -> list.forEach(listener -> listener.onRuleEvaluated(rule, resource)));
  }

  private void triggerListenersOnNotEvaluated(Rule rule, Resource resource) {
    Optional.ofNullable(ruleListeners).ifPresent( list -> list.forEach(listener -> listener.onRuleNotEvaluated(rule, resource)));
  }

  private void triggerListenersOnCompleted(Resource resource) {
    Optional.ofNullable(ruleListeners).ifPresent( list -> list.forEach(listener -> listener.onComplete(resource)));
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

  @Override
  public void addRules(List<Rule> rules) {
    this.rules.addAll(rules);
  }
}
