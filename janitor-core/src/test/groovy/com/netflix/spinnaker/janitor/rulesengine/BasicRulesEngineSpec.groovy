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

import com.netflix.spinnaker.janitor.model.Resource
import com.netflix.spinnaker.janitor.model.Rule
import com.netflix.spinnaker.janitor.model.ResourceTypes
import spock.lang.Specification
import spock.lang.Subject

class BasicRulesEngineSpec extends Specification {

  @Subject
  BasicRulesEngine rulesEngine

  def "should aggregate evaluated rules summaries"() {
    given:
    def (first, second, third) = [
      createRule('test rule 1',true),
      createRule('test rule 2',false),
      createRule('test rule 3', true)
    ]

    rulesEngine = new BasicRulesEngine([], 15, ["resourceToExclude"] as Set)
      .addRule(third)
      .addRule(first)
      .addRule(second)

    and:
    def resource = Mock(Resource)
    resource.getName() >> "app-elb"
    resource.getResourceType() >> ResourceTypes.LOADBALANCER

    when:
    Result result = rulesEngine.run(resource)

    then:
    !result.valid
    result.summaries.size() == 2
    result.summaries*.description.contains("test rule 1")
    result.summaries*.description.contains("test rule 3")
    !result.summaries*.description.contains("test rule 2")
  }

  def "should trigger rule listeners when a rule evaluates"() {
    given:
    def resource = Mock(Resource)
    def (first, second, third) = [
      createRule('test rule 1',true),
      createRule('test rule 2',false),
      createRule('test rule 3', true)
    ]

    and:
    def listener = Mock(RuleListener)
    rulesEngine = new BasicRulesEngine([listener], 15, ["resourceToExclude"] as Set)
      .addRule(third)
      .addRule(first)
      .addRule(second)

    when:
    rulesEngine.run(resource)

    then: "rules should be sorted"
    rulesEngine.rules.size() == 3

    and:
    1 * listener.onRuleNotEvaluated(second, resource)
    1 * listener.onRuleEvaluated(first, resource)
    1 * listener.onRuleEvaluated(third, resource)
    1 * listener.onComplete(resource)
  }

  private static Rule createRule(String name, boolean evaluates = false) {
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
      boolean supports(String type) {
        return true
      }
    }
  }
}
