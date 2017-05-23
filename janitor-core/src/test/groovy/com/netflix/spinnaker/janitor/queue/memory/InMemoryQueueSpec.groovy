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

package com.netflix.spinnaker.janitor.queue.memory

import com.netflix.spinnaker.janitor.queue.MarkMessage
import com.netflix.spinnaker.janitor.queue.Message
import com.netflix.spinnaker.janitor.queue.MessageCallback
import spock.lang.Specification
import spock.lang.Subject
import spock.util.concurrent.PollingConditions

import java.time.Clock
import java.time.Duration
import java.time.temporal.TemporalAmount
import java.util.function.Function

class InMemoryQueueSpec extends Specification {
  Clock systemClock = Clock.systemDefaultZone()
  TemporalAmount ackTimeout = Duration.ofSeconds(1)
  long initialDeliveryDelayInSeconds = 1
  long redeliverDelayInSeconds = 1
  int redeliveryCount = 1

  @Subject
  InMemoryQueue queue = new InMemoryQueue(
    systemClock,
    ackTimeout,
    initialDeliveryDelayInSeconds,
    redeliverDelayInSeconds,
    redeliveryCount
  )

  def "should push a message onto the work queue"() {
    when:
    queue.push(new MarkMessage(
      "aws",
      "model",
      "accountId1"), Duration.ofSeconds(0)
    )

    then:
    queue.size() == 1
  }

  def "should poll message from work queue"() {
    given:
    def consumer = Mock(MessageCallback)
    def message = new MarkMessage(
      "aws",
      "model",
      "accountId1"
    )

    and: "a message is pushed onto the queue with no delay"
    queue.push(message, Duration.ofSeconds(0))

    when:
    queue.poll(consumer)

    then: "message should be immediately passed to consumer"
    1 * consumer.accept(message,_)

    and: "a message is pushed onto the queue with delay"
    queue.push(message, Duration.ofHours(1))

    then: "message will not be passed to the consumer until delay is reached"
    0 * consumer.accept(message,_)
  }

  def "should acknowledge a message"() {
    given:
    def message = new MarkMessage(
      "aws",
      "model",
      "accountId1"
    )

    Boolean acked = null
    MessageCallback consumer = new MessageCallback() {
      @Override
      void accept(Message m, Function<Message, Boolean> ack) {
        assert(message.equals(m))
        assert queue.waitQueue.size() == 1 // message is placed on wait queue at poll
        acked = ack.apply(m)
        assert queue.waitQueue.size() == 0 // message is removed from wait queue when acked
      }
    }


    and: "a message is pushed onto the queue with no delay"
    queue.push(message, Duration.ofSeconds(0))
    assert queue.size() == 1

    when:
    queue.poll(consumer)

    then:
    acked
    queue.size() == 0
  }

  def "should redeliver an unacked message"() {
    given:
    def conditions = new PollingConditions(timeout: 2, initialDelay: initialDeliveryDelayInSeconds, delay: redeliverDelayInSeconds, factor: 1.5)
    def message = new MarkMessage(
      "aws",
      "model",
      "accountId1"
    )

    MessageCallback consumer = new MessageCallback() {
      @Override
      void accept(Message m, Function<Message, Boolean> ack) {
        assert(message.equals(m))
      }
    }

    and: "a message is pushed onto the queue with no delay"
    queue.push(message, Duration.ofSeconds(0))
    assert queue.size() == 1

    when:
    queue.poll(consumer)

    then:
    conditions.eventually {
      assert queue.workQueue.size() == 1
      assert queue.waitQueue.size() == 0
    }

    queue.size() == 1

  }
}
