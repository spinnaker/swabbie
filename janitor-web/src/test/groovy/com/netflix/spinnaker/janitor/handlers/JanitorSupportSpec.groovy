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

package com.netflix.spinnaker.janitor.handlers

import com.netflix.spinnaker.janitor.model.EntityTag
import com.netflix.spinnaker.janitor.model.LoadBalancer
import com.netflix.spinnaker.janitor.model.ResourceTagger
import com.netflix.spinnaker.janitor.model.Action
import com.netflix.spinnaker.janitor.queue.CleanupMessage
import com.netflix.spinnaker.janitor.queue.JanitorQueue
import com.netflix.spinnaker.janitor.queue.MarkMessage
import com.netflix.spinnaker.janitor.rulesengine.Result
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine
import spock.lang.Specification
import spock.lang.Subject

import java.time.Clock
import java.time.Duration
import java.time.LocalDate

class JanitorSupportSpec extends Specification {
  JanitorQueue janitorQueue = Mock(JanitorQueue)
  RulesEngine rulesEngine = Mock(RulesEngine)
  ResourceTagger resourceTagger = Mock(ResourceTagger)
  Clock clock = Clock.systemDefaultZone()

  @Subject
  JanitorSupport janitorSupport = new JanitorSupport()

  def "should skip cleanup if resource has never been marked"() {
    given:
    def message = new CleanupMessage(
      "aws",
      "loadbalancer",
      "accountId1",
      "resourceId",
      "elb-name",
      "us-west-1",
    )

    and:
    def cleanupAction = Mock(Action)
    resourceTagger.find("resourceId", "elb-name", "loadbalancer") >> null

    when:
    janitorSupport.cleanupResource(message, resourceTagger, clock, cleanupAction)

    then:
    0 * cleanupAction.invoke(_)
    0 * resourceTagger.upsert(_,_,_,_,_,_)
  }

  def "should cleanup a resource that was previously marked and is scheduled to be deleted now"() {
    given:
    def message = new CleanupMessage(
      "aws",
      "loadbalancer",
      "accountId1",
      "resourceId",
      "elb-name",
      "us-west-1",
    )

    and:
    def tag = new EntityTag()
    tag.setMarked("unused resource", LocalDate.now(clock))
    tag.setScheduledTerminationAt(LocalDate.now(clock))

    def cleanupAction = Mock(Action)
    resourceTagger.find("resourceId", "elb-name", "loadbalancer") >> tag

    when:
    janitorSupport.cleanupResource(message, resourceTagger, clock, cleanupAction)

    then:
    1 * cleanupAction.invoke(_)
    1 * resourceTagger.upsert({ it.getTerminated() == true} as EntityTag,"resourceId", "loadbalancer", "accountId1", "us-west-1", "aws")
  }

  def "should mark a violating resource seen for the first time"() {
    given:
    def resource = Mock(LoadBalancer)
    def message = new MarkMessage(
      "aws",
      "loadbalancer",
      "accountId1"
    )

    def result = new Result(
      valid: false,
      summaries: [new Result.Summary("unused load balancer", LocalDate.now(clock))]
    )

    and:
    resource.getId() >> "resourceId"
    resource.getRegion() >> "us-west-1"
    rulesEngine.run(resource) >> result

    when:
    janitorSupport.markResource(message, resource, rulesEngine, resourceTagger, clock, janitorQueue)

    then:
    1 * janitorQueue.push(_ as CleanupMessage, _)
    1 * resourceTagger.upsert(
      { it.getMarked() == true } as EntityTag,
      _ as String,
      "loadbalancer",
      "accountId1",
      "us-west-1",
      "aws"
    )

  }

  def "should skip an opted out resource"() {
    //TODO: opting out should have a TTL, if a resource is opted out, we should
    //TODO: we check if it has been 15 days (default), then we need to re-notify the user and schedule resource for deletion

    given:
    def resource = Mock(LoadBalancer)
    def message = new MarkMessage(
      "aws",
      "loadbalancer",
      "accountId1"
    )

    def result = new Result(
      valid: false,
      summaries: [new Result.Summary("unused load balancer", LocalDate.now(clock))]
    )

    def tag = new EntityTag()
    tag.setOptedout(LocalDate.now(clock))

    and:
    resource.getId() >> "resourceId"
    resource.getRegion() >> "us-west-1"
    rulesEngine.run(resource) >> result

    when:
    janitorSupport.markResource(message, resource, rulesEngine, resourceTagger, clock, janitorQueue)

    then:
    1 * resourceTagger.find(_,_,_) >> tag
    0 * resourceTagger.upsert(_,_,_,_,_,_)
  }

  def "should mark and schedule resource deletion if resource was not already marked"() {
    given:
    def resource = Mock(LoadBalancer)
    def message = new MarkMessage(
      "aws",
      "loadbalancer",
      "accountId1"
    )

    def result = new Result(
      valid: false,
      summaries: [new Result.Summary("unused load balancer", LocalDate.now(clock))]
    )

    def tag = new EntityTag()

    and:
    resource.getId() >> "resourceId"
    resource.getRegion() >> "us-west-1"
    rulesEngine.run(resource) >> result

    when:
    janitorSupport.markResource(message, resource, rulesEngine, resourceTagger, clock, janitorQueue)

    then:
    1 * resourceTagger.find(_,_,_) >> new EntityTag()
    1 * janitorQueue.push(_ as CleanupMessage, Duration.ofSeconds(0))
    1 * resourceTagger.upsert(
      { it.getMarked() == true } as EntityTag,
      _ as String,
      "loadbalancer",
      "accountId1",
      "us-west-1",
      "aws"
    )
  }

  def "should schedule resource deletion right away if scheduled termination is now or has passed"() {
    given:
    def resource = Mock(LoadBalancer)
    def message = new MarkMessage(
      "aws",
      "loadbalancer",
      "accountId1"
    )

    def result = new Result(
      valid: false,
      summaries: [new Result.Summary("unused load balancer", LocalDate.now(clock))]
    )

    def tag = new EntityTag()
    tag.setMarked("unused resource", LocalDate.now(clock))
    tag.setScheduledTerminationAt(LocalDate.now(clock).minusDays(2))

    and:
    resource.getId() >> "resourceId"
    resource.getRegion() >> "us-west-1"
    rulesEngine.run(resource) >> result

    when:
    janitorSupport.markResource(message, resource, rulesEngine, resourceTagger, clock, janitorQueue)

    then:
    1 * resourceTagger.find(_,_,_) >> tag
    1 * janitorQueue.push(_ as CleanupMessage, Duration.ofSeconds(0))
    1 * resourceTagger.upsert(
      { it.getMarked() == true } as EntityTag,
      _ as String,
      "loadbalancer",
      "accountId1",
      "us-west-1",
      "aws"
    )
  }
}
