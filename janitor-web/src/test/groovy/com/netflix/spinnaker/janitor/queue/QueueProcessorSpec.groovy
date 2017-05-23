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

package com.netflix.spinnaker.janitor.queue

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.StatusChangeEvent
import com.netflix.spectator.api.Counter
import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.janitor.handlers.MessageHandlerNotFoundException
import com.netflix.spinnaker.janitor.model.ResourceTagger
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import spock.lang.Specification
import spock.lang.Subject

import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.function.Function

class QueueProcessorSpec extends Specification {
  JanitorQueue janitorQueue = Mock(JanitorQueue)
  MessageHandler handler = Mock(MessageHandler)
  RulesEngine rulesEngine = Mock(RulesEngine)
  ResourceTagger resourceTagger = Mock(ResourceTagger)
  Registry registry = Mock(Registry)
  Clock clock = Mock(Clock)

  @Subject
  QueueProcessor queueProcessor = new QueueProcessor(
    janitorQueue,
    registry,
    new BlockingThreadExecutor(),
    clock,
    resourceTagger,
    [handler],
    rulesEngine
  )

  def "should not poll queue when not up in discovery"() {
    given:
    queueProcessor.onApplicationEvent(
      new RemoteStatusChangedEvent(
        new StatusChangeEvent(
          InstanceInfo.InstanceStatus.UP,
          InstanceInfo.InstanceStatus.OUT_OF_SERVICE
        )
      )
    )

    when:
    queueProcessor.poll()

    then:
    0 * janitorQueue.poll(_)
  }

  def "should handle marking a resource for deletion"() {
    given:
    def message = new MarkMessage(
      "aws",
      "loadbalancer",
      "accountId1"
    )

    def ack = Mock(Function)

    and:
    handler.supports(message) >> true

    when:
    queueProcessor.process(message, ack)

    then:
    1 * handler.handleMark(message as MarkMessage, rulesEngine, resourceTagger, clock, janitorQueue)
    1 * ack.apply(message)
  }

  def "should handle cleaning a resource"() {
    given:
    def message = new CleanupMessage(
      "aws",
      "loadbalancer",
      "accountId1",
      "resourceId",
      "elb-name",
      "us-west-1",
    )

    def ack = Mock(Function)

    and:
    handler.supports(message) >> true

    when:
    queueProcessor.process(message, ack)

    then:
    1 * handler.handleCleanup(message as CleanupMessage, resourceTagger, clock)
    1 * ack.apply(message)
  }

  def "should find a message handler"() {
    given:
    def message = new MarkMessage(
      "aws",
      "loadbalancer",
      "accountId1"
    )

    and:
    handler.supports(message) >> true

    when:
    queueProcessor.process(message, Mock(Function))

    then:
    notThrown(MessageHandlerNotFoundException)
  }

  def "should throw an exception if no message handler is found"() {
    given:
    def message = new MarkMessage(
      "aws",
      "loadbalancer",
      "accountId1"
    )

    and:
    handler.supports(message) >> false

    when:
    queueProcessor.process(message, Mock(Function))

    then:
    thrown(MessageHandlerNotFoundException)
  }

  class BlockingThreadExecutor implements Executor {
    Executor delegate = Executors.newSingleThreadExecutor()
    @Override
    void execute(Runnable command) {
      CountDownLatch latch = new CountDownLatch(1)
      delegate.execute {
        try {
          command.run()
        } finally {
          latch.countDown()
        }
      }

      latch.await()
    }
  }
}
