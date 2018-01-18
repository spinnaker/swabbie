/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.handlers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.netflix.spinnaker.swabbie.Notifier
import com.netflix.spinnaker.swabbie.ResourceRepository
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription
import com.netflix.spinnaker.swabbie.scheduler.RetentionPolicy
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock

object ResourceHandlerTest {
  val resourceRepository = mock<ResourceRepository>()
  val notifier = mock<Notifier>()
  val clock = Clock.systemDefaultZone()

  @AfterEach
  fun cleanup() {
    reset(resourceRepository, notifier)
  }

  @Test
  fun `creating work by tracking new violating resources and notify user`() {
    val mark = MarkResourceDescription("aws:test:us-east-1", SECURITY_GROUP, "aws", RetentionPolicy(emptyList(), 10))
    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    TestResourceHandler(
      listOf<Rule>(AlwaysInvalidRule()),
      resourceRepository,
      notifier
    ).process(mark)

    verify(notifier).notify(any(), any())
    verify(resourceRepository).track(any(), any())
  }

  @Test
  fun `should update already tracked resource if still invalid and don't notify user again`() {
    val mark = MarkResourceDescription("aws:test:us-east-1", SECURITY_GROUP, "aws", RetentionPolicy(emptyList(), 10))
    whenever(resourceRepository.getMarkedResources()) doReturn
      listOf(
        TrackedResource(
          TestResource("test resource"),
          listOf(Summary("violates rule 1", "ruleName")),
          Notification(clock.millis(), "yolo@netflixcom", "Email" ),
          clock.millis()
        )
      )

    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    TestResourceHandler(
      listOf<Rule>(AlwaysInvalidRule()),
      resourceRepository,
      notifier
    ).process(mark)

    verify(notifier, never()).notify(any(), any())
    verify(resourceRepository).track(any(), any())
  }

  //TODO: fix this
//  @Test
  fun `should forget resource if no longer violate a rule and don't notify user`() {
    val mark = MarkResourceDescription("aws:test:us-east-1", SECURITY_GROUP, "aws", RetentionPolicy(emptyList(), 10))
    whenever(resourceRepository.getMarkedResources()) doReturn
      listOf(
        TrackedResource(
          TestResource("test resource"),
          listOf(Summary("resource micro-aggressions here", javaClass.simpleName)),
          Notification(clock.millis(), "yolo@netflixcom", "Email" ),
          clock.millis()
        )
      )

    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    TestResourceHandler(
      listOf(AlwaysValidRule()),
      resourceRepository,
      notifier
    ).process(mark)

    verify(notifier, never()).notify(any(), any())
    verify(resourceRepository, never()).track(any(), any())
    verify(resourceRepository).remove(any())
  }

  class AlwaysInvalidRule: Rule {
    override fun applies(resource: Resource): Boolean {
      return true
    }

    override fun apply(resource: Resource): Result {
      return Result(Summary("resource micro-aggressions here", javaClass.simpleName))
    }
  }

  class AlwaysValidRule: Rule {
    override fun applies(resource: Resource): Boolean {
      return true
    }

    override fun apply(resource: Resource): Result {
      return Result(null)
    }
  }

  class TestResourceHandler(
    rules: List<Rule>,
    resourceRepository: ResourceRepository,
    notifier: Notifier
  ) : AbstractResourceHandler(rules, resourceRepository, notifier) {
    override fun handles(markResourceDescription: MarkResourceDescription): Boolean {
      return true
    }

    override fun getNameSpace(): String {
      return "test:us-east-1"
    }

    override fun fetchResources(markResourceDescription: MarkResourceDescription): List<Resource>? {
      return listOf(TestResource("test resource"))
    }
  }
}
