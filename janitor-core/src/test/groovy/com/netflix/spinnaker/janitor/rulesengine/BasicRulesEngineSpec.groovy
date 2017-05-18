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

package com.netflix.spinnaker.janitor.rulesengine

import com.netflix.spinnaker.janitor.BasicResource
import com.netflix.spinnaker.janitor.Resource
import com.netflix.spinnaker.janitor.Rule
import spock.lang.Specification
import spock.lang.Subject

class BasicRulesEngineSpec extends Specification {

  @Subject
  BasicRulesEngine rulesEngine

  def "should sort rules in the order they will be applied"() {
    given:
    def (first, second, third) = [
      createRule('test rule 1', 0, true),
      createRule('test rule 2', 1, false),
      createRule('test rule 3', 2, true)
    ]

    and:
    rulesEngine = new BasicRulesEngine([])
      .addRule(third)
      .addRule(first)
      .addRule(second)

    when:
    rulesEngine.sortRules()

    then:
    rulesEngine.rules.size() == 3
    rulesEngine.rules[0] == first
    rulesEngine.rules[1] == second
    rulesEngine.rules[2] == third
  }

  def "should trigger rule listeners when a rule evaluates"() {
    given:
    def resource = new BasicResource()
    def (first, second, third) = [
      createRule('test rule 1', 0, true),
      createRule('test rule 2', 1, false),
      createRule('test rule 3', 2, true)
    ]

    and:
    def listener = Mock(RuleListener)
    rulesEngine = new BasicRulesEngine([listener])
      .addRule(third)
      .addRule(first)
      .addRule(second)

    when:
    rulesEngine.run(resource)

    then: "rules should be sorted"
    rulesEngine.rules.size() == 3
    rulesEngine.rules[0] == first
    rulesEngine.rules[1] == second
    rulesEngine.rules[2] == third

    and:
    1 * listener.onRuleNotEvaluated(second, resource)
    1 * listener.onRuleEvaluated(first, resource)
    1 * listener.onRuleEvaluated(third, resource)
    1 * listener.onComplete(resource)
  }

  private static Rule createRule(String name, int priority, boolean evaluates = false) {
    return new Rule() {
      @Override
      String getName() {
        return name
      }

      @Override
      String getDescription() {
        return name
      }

      @Override
      boolean checkResource(Resource resource) {
        return evaluates
      }


      @Override
      int getPriority() {
        return priority;
      }

      @Override
      boolean supports(String type) {
        return true;
      }
    }
  }
}
