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
import com.netflix.spinnaker.config.Retention
import com.netflix.spinnaker.swabbie.ScopeOfWorkConfiguration
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.persistence.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock

object ResourceHandlerTest {
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val clock = Clock.systemDefaultZone()
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()

  @AfterEach
  fun cleanup() {
    reset(resourceRepository, applicationEventPublisher)
  }

  @Test
  fun `should track violating resources and notify user`() {
    val resource = TestResource("testResource")
    val violationSummary = Summary("violates rule 1", "ruleName")
    TestResourceHandler(
      clock = clock,
      rules =listOf<Rule>(TestRule(true, violationSummary)),
      resourceTrackingRepository = resourceRepository,
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = mutableListOf(resource)
    ).mark(
      ScopeOfWorkConfiguration(
        namespace = "${resource.cloudProvider}:test:us-east-1:${resource.resourceType}",
        account = Account(name = "test", accountId = "id"),
        location = "us-east-1",
        resourceType = resource.resourceType,
        cloudProvider = resource.cloudProvider,
        retention = Retention(
          days = 10,
          ageThresholdDays = 3
        ),
        dryRun = false,
        exclusions = emptyList()
      )
    )

    verify(resourceRepository).upsert(any(), any())
  }

  @Test
  fun `should update already tracked resource if still invalid and don't generate a mark event again`() {
    val resource = TestResource("testResource")
    val configuration = ScopeOfWorkConfiguration(
      namespace = "${resource.cloudProvider}:test:us-east-1:${resource.resourceType}",
      account = Account(name = "test", accountId = "id"),
      location = "us-east-1",
      cloudProvider = resource.cloudProvider,
      resourceType = resource.resourceType,
      retention = Retention(
        days = 10,
        ageThresholdDays = 3
      ),
      dryRun = false,
      exclusions = emptyList()
    )

    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("violates rule 1", "ruleName")),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis(),
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflix.com",
        notificationType = "Email",
        notificationStamp = clock.millis()
      )
    )

    whenever(resourceRepository.find(markedResource.resourceId, markedResource.namespace)) doReturn
      markedResource

    TestResourceHandler(
      clock = clock,
      rules = listOf<Rule>(TestRule(true, Summary("always invalid", "rule1"))),
      resourceTrackingRepository = resourceRepository,
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = mutableListOf(resource)
    ).mark(configuration)

    verify(applicationEventPublisher, never()).publishEvent(MarkResourceEvent(markedResource, configuration))
    verify(resourceRepository).upsert(any(), any())
  }

  @Test
  fun `should delete a resource`() {
    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    val resource = TestResource("marked resource due for deletion now")
    val configuration = ScopeOfWorkConfiguration(
      namespace = "${resource.cloudProvider}:test:us-east-1:${resource.resourceType}",
      account = Account(name = "test", accountId = "id"),
      location = "us-east-1",
      cloudProvider = resource.cloudProvider,
      resourceType = resource.resourceType,
      retention = Retention(
        days = 10,
        ageThresholdDays = 3
      ),
      dryRun = false,
      exclusions = emptyList()
    )

    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("invalid resource 1", "rule 1")),
      namespace = configuration.namespace,
      projectedDeletionStamp = fifteenDaysAgo,
      adjustedDeletionStamp = fifteenDaysAgo,
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflix.com",
        notificationType = "Email",
        notificationStamp = clock.millis()
      )
    )

    val fetchedResources = mutableListOf<Resource>(resource)
    TestResourceHandler(
      clock = clock,
      rules = listOf(
        TestRule(true, Summary("always invalid", "rule1")),
        TestRule(true, null),
        TestRule(false, null)
      ),
      resourceTrackingRepository = resourceRepository,
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = fetchedResources
    ).clean(markedResource, configuration)

    verify(resourceRepository, never()).upsert(any(), any())
    fetchedResources.size shouldMatch equalTo(0)
    verify(resourceRepository).remove(any())
  }

  @Test
  fun `should forget resource if no longer violate a rule and don't notify user`() {
    val resource = TestResource("testResource")
    val configuration = ScopeOfWorkConfiguration(
      namespace = "${resource.cloudProvider}:test:us-east-1:${resource.resourceType}",
      account = Account(name = "test", accountId = "id"),
      location = "us-east-1",
      cloudProvider = resource.cloudProvider,
      resourceType = resource.resourceType,
      retention = Retention(
        days = 10,
        ageThresholdDays = 3
      ),
      dryRun = false,
      exclusions = emptyList()
    )

    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("invalid resource", javaClass.simpleName)),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis(),
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflix.com",
        notificationType = "Email",
        notificationStamp = clock.millis()
      )
    )

    whenever(resourceRepository.find(markedResource.resourceId, markedResource.namespace)) doReturn
      markedResource


    TestResourceHandler(
      clock = clock,
      rules = listOf(TestRule(true, null)),
      resourceTrackingRepository = resourceRepository,
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = mutableListOf(resource)
    ).mark(configuration)

    verify(applicationEventPublisher).publishEvent(UnMarkResourceEvent(markedResource, configuration))
    verify(resourceRepository, never()).upsert(any(), any())
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
    clock: Clock,
    rules: List<Rule>,
    resourceTrackingRepository: ResourceTrackingRepository,
    applicationEventPublisher: ApplicationEventPublisher,
    private val simulatedUpstreamResources: MutableList<Resource>?
  ) : AbstractResourceHandler(clock, rules, resourceTrackingRepository, applicationEventPublisher) {
    override fun remove(markedResource: MarkedResource, scopeOfWorkConfiguration: ScopeOfWorkConfiguration) {
      simulatedUpstreamResources?.removeIf { markedResource.resourceId == it.resourceId }
    }

    // simulates querying for a resource upstream
    override fun getUpstreamResource(markedResource: MarkedResource, scopeOfWorkConfiguration: ScopeOfWorkConfiguration): Resource? {
      return simulatedUpstreamResources?.find { markedResource.resourceId == it.resourceId}
    }

    override fun handles(resourceType: String, cloudProvider: String): Boolean {
      return true
    }

    override fun getUpstreamResources(scopeOfWorkConfiguration: ScopeOfWorkConfiguration): List<Resource>? {
      return simulatedUpstreamResources
    }
  }
}
