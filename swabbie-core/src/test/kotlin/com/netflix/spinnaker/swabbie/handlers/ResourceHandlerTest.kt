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

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
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
    val fetchedResources = mutableListOf<Resource>(
      TestResource("marked resource due for deletion now")
    )

    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    TestResourceHandler(
      listOf<Rule>(TestRule(true, Summary("always invalid", "rule1"))),
      resourceRepository,
      notifier,
      fetchedResources
    ).mark(
      MarkResourceDescription(
        "aws:test:us-east-1",
        SECURITY_GROUP,
        "aws",
        RetentionPolicy(emptyList(), 10)
      )
    )

    verify(notifier).notify(any(), any())
    verify(resourceRepository).track(any(), any())
  }

  @Test
  fun `should update already tracked resource if still invalid and don't notify user again`() {
    val fetchedResources = mutableListOf<Resource>(
      TestResource("testResource")
    )

    whenever(resourceRepository.getMarkedResources()) doReturn
      listOf(
        MarkedResource(
          TestResource("testResource"),
          listOf(Summary("violates rule 1", "ruleName")),
          Notification(clock.millis(), "yolo@netflixcom", "Email" ),
          clock.millis()
        )
      )

    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    TestResourceHandler(
      listOf<Rule>(TestRule(true, Summary("always invalid", "rule1"))),
      resourceRepository,
      notifier,
      fetchedResources
    ).mark(
      MarkResourceDescription(
        "aws:test:us-east-1",
        SECURITY_GROUP,
        "aws",
        RetentionPolicy(emptyList(), 10))
    )

    verify(notifier, never()).notify(any(), any())
    verify(resourceRepository).track(any(), any())
  }

  @Test
  fun `should delete a resource`() {
  val now = 0L
    val resourceToDelete = MarkedResource(
      TestResource("marked resource due for deletion now"),
      listOf(Summary("invalid resource 1", "rule 1")),
      Notification(clock.instant().toEpochMilli(), "yolo@netflixcom", "Email" ),
      now
    )

    val fetchedResources = mutableListOf<Resource>(
      TestResource("marked resource due for deletion now")
    )

    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    TestResourceHandler(
      listOf(
        TestRule(true, Summary("always invalid", "rule1")),
        TestRule(true, null),
        TestRule(false, null)
      ),
      resourceRepository,
      notifier,
      fetchedResources
    ).cleanup(resourceToDelete)

    verify(notifier, never()).notify(any(), any())
    verify(resourceRepository, never()).track(any(), any())
    fetchedResources.size shouldMatch equalTo(0)
    verify(resourceRepository).remove(any())
  }

  @Test
  fun `should forget resource if no longer violate a rule and don't notify user`() {
    val fetchedResources = mutableListOf<Resource>(
      TestResource("testResource")
    )

    whenever(resourceRepository.getMarkedResources()) doReturn
      listOf(
        MarkedResource(
          TestResource("testResource"),
          listOf(Summary("resource micro-aggressions here", javaClass.simpleName)),
          Notification(clock.millis(), "yolo@netflixcom", "Email" ),
          clock.millis()
        )
      )

    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    TestResourceHandler(
      listOf(TestRule(true, null)),
      resourceRepository,
      notifier,
      fetchedResources
    ).mark(
      MarkResourceDescription(
        "aws:test:us-east-1",
        SECURITY_GROUP, "aws",
        RetentionPolicy(emptyList(), 10)
      )
    )

    verify(notifier, never()).notify(any(), any())
    verify(resourceRepository, never()).track(any(), any())
    verify(resourceRepository).remove(any())
  }

  class TestRule(
    private val applies: Boolean,
    private val summary: Summary?
  ): Rule {
    override fun applies(resource: Resource): Boolean {
      return applies
    }

    override fun apply(resource: Resource): Result {
      return Result(summary)
    }
  }

  class TestResourceHandler(
    rules: List<Rule>,
    resourceRepository: ResourceRepository,
    notifier: Notifier,
    private val resources: MutableList<Resource>?
  ) : AbstractResourceHandler(rules, resourceRepository, notifier) {

    // simulates removing a resource
    override fun doDelete(markedResource: MarkedResource) {
      resources?.removeIf { markedResource.resourceId == it.resourceId }
    }

    // simulates querying for a resource upstream
    override fun fetchResource(markedResource: MarkedResource): Resource? {
      return resources?.find { markedResource.resourceId == it.resourceId}
    }

    override fun handles(resourceType: String, cloudProvider: String): Boolean {
      return true
    }

    override fun getNameSpace(): String {
      return "test:us-east-1"
    }

    override fun fetchResources(markResourceDescription: MarkResourceDescription): List<Resource>? {
      return resources
    }
  }
}
