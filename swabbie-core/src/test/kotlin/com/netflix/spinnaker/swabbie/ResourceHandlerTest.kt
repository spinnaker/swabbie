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

package com.netflix.spinnaker.swabbie

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.NameExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_PROVIDER_TYPE
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_TYPE
import com.netflix.spinnaker.swabbie.test.TestResource
import com.netflix.spinnaker.swabbie.work.WorkConfiguration
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock

object ResourceHandlerTest {
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val clock = Clock.systemDefaultZone()
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val postAction: (resource: List<Resource>) -> Unit = {
    println("swabbie post action on $it")
  }

  @AfterEach
  fun cleanup() {
    reset(resourceRepository, applicationEventPublisher)
  }

  @Test
  fun `should track a violating resource and notify user`() {
    val resource = TestResource("testResource")
    TestResourceHandler(
      clock = clock,
      rules = listOf(TestRule(
        invalidOn = { resource.resourceId == "testResource" },
        summary = Summary("violates rule 1", "ruleName"))
      ),
      resourceTrackingRepository = resourceRepository,
      exclusionPolicies = listOf(mock()),
      ownerResolver = mock(),
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = mutableListOf(resource)
    ).mark(
      workConfiguration = workConfiguration(),
      postMark = { postAction(listOf(resource)) }
    )

    inOrder(resourceRepository, applicationEventPublisher) {
      verify(resourceRepository).upsert(any(), any())
      verify(applicationEventPublisher).publishEvent(any<MarkResourceEvent>())
    }
  }

  @Test
  fun `should track violating resources and notify user`() {
    val resources: List<TestResource> = listOf(
      TestResource("invalid resource 1"),
      TestResource("invalid resource 2"),
      TestResource("valid resource")
    )

    val rules: List<TestRule> = resources.map {
      TestRule(
        invalidOn = { it.resourceId == "invalid resource 1" || it.resourceId == "invalid resource 2" },
        summary = Summary(description = "test rule description", ruleName = "test rule name")
      )
    }

    TestResourceHandler(
      clock = clock,
      rules = rules,
      resourceTrackingRepository = resourceRepository,
      exclusionPolicies = listOf(mock()),
      ownerResolver = mock(),
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = resources.toMutableList()
    ).mark(
      workConfiguration = workConfiguration(),
      postMark = { postAction(resources) }
    )

    verify(resourceRepository, atMost(maxNumberOfInvocations = 2)).upsert(any(), any())
    verify(applicationEventPublisher, atMost(maxNumberOfInvocations = 2)).publishEvent(any<MarkResourceEvent>())
  }

  @Test
  fun `should update already tracked resource if still invalid and don't generate a mark event again`() {
    val resource = TestResource("testResource")
    val configuration = workConfiguration()
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
      rules = listOf(
        TestRule(
          invalidOn = { resource.resourceId == "testResource" },
          summary = Summary("always invalid", "rule1")
        )
      ),
      resourceTrackingRepository = resourceRepository,
      ownerResolver = mock(),
      exclusionPolicies = listOf(mock()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = mutableListOf(resource)
    ).mark(
      workConfiguration = configuration,
      postMark = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, never()).publishEvent(MarkResourceEvent(markedResource, configuration))
    verify(resourceRepository, atMost(maxNumberOfInvocations = 1)).upsert(any(), any())
  }

  @Test
  fun `should delete a resource`() {
    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    val resource = TestResource("marked resource due for deletion now")
    val configuration = workConfiguration()
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

    val fetchedResources = mutableListOf<TestResource>(resource)
    TestResourceHandler(
      clock = clock,
      rules = listOf(
        TestRule({ true }, Summary("always invalid", "rule1"))
      ),
      resourceTrackingRepository = resourceRepository,
      ownerResolver = mock(),
      exclusionPolicies = listOf(mock()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = fetchedResources
    ).clean(
      markedResource = markedResource,
      workConfiguration = configuration,
      postClean = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, atMost(maxNumberOfInvocations = 1)).publishEvent(DeleteResourceEvent(markedResource, configuration))
    verify(resourceRepository, atMost(maxNumberOfInvocations = 1)).remove(any())
    fetchedResources.size shouldMatch equalTo(0)
  }

  @Test
  fun `should ignore resource using exclusion strategies`() {
    val resource = TestResource(resourceId = "testResource", name = "testResourceName")
    // configuration with a name exclusion strategy
    val configuration = workConfiguration(exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Name.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("name")
              .withValue(listOf(resource.name))
          )
        )
    ))

    TestResourceHandler(
      clock = clock,
      rules = listOf(
        TestRule({ true }, Summary("always invalid", "rule1"))
      ),
      resourceTrackingRepository = resourceRepository,
      ownerResolver = mock(),
      exclusionPolicies = listOf(NameExclusionPolicy()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = mutableListOf(resource)
    ).mark(
      workConfiguration = configuration,
      postMark = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should forget resource if no longer violate a rule and don't notify user`() {
    val resource = TestResource("testResource")
    val configuration = workConfiguration()
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
      rules = listOf(
        TestRule(invalidOn = { true }, summary = null)
      ),
      resourceTrackingRepository = resourceRepository,
      ownerResolver = mock(),
      exclusionPolicies = listOf(mock()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedUpstreamResources = mutableListOf(resource)
    ).mark(
      workConfiguration = configuration,
      postMark = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, atMost(maxNumberOfInvocations = 1)).publishEvent(UnMarkResourceEvent(markedResource, configuration))
    verify(resourceRepository, atMost(maxNumberOfInvocations = 1)).remove(any())

    verify(resourceRepository, never()).upsert(any(), any())
  }

  internal fun workConfiguration(exclusions: List<Exclusion> = emptyList()): WorkConfiguration = WorkConfiguration(
    namespace = "$TEST_RESOURCE_PROVIDER_TYPE:test:us-east-1:$TEST_RESOURCE_TYPE",
    account = SpinnakerAccount(name = "test", accountId = "id", type = "type"),
    location = "us-east-1",
    resourceType = TEST_RESOURCE_TYPE,
    cloudProvider = TEST_RESOURCE_PROVIDER_TYPE,
    retentionDays = 14,
    dryRun = false,
    exclusions = exclusions
  )

  class TestRule(
    private val invalidOn: (Resource) -> Boolean,
    private val summary: Summary?
  ) : Rule<TestResource> {
    override fun apply(resource: TestResource): Result {
      return if (invalidOn(resource)) Result(summary) else Result(null)
    }
  }

  class TestResourceHandler(
    clock: Clock,
    resourceTrackingRepository: ResourceTrackingRepository,
    ownerResolver: OwnerResolver,
    applicationEventPublisher: ApplicationEventPublisher,
    exclusionPolicies: List<ResourceExclusionPolicy>,
    private val rules: List<Rule<TestResource>>,
    private val simulatedUpstreamResources: MutableList<TestResource>?,
    registry: Registry = NoopRegistry()
  ) : AbstractResourceHandler<TestResource>(registry, clock, rules, resourceTrackingRepository, exclusionPolicies, ownerResolver, applicationEventPublisher) {
    override fun remove(markedResource: MarkedResource, workConfiguration: WorkConfiguration) {
      simulatedUpstreamResources?.removeIf { markedResource.resourceId == it.resourceId }
    }

    // simulates querying for a resource upstream
    override fun getUpstreamResource(markedResource: MarkedResource, workConfiguration: WorkConfiguration): TestResource? {
      return simulatedUpstreamResources?.find { markedResource.resourceId == it.resourceId }
    }

    override fun handles(workConfiguration: WorkConfiguration): Boolean {
      return !rules.isEmpty()
    }

    override fun getUpstreamResources(workConfiguration: WorkConfiguration): List<TestResource>? {
      return simulatedUpstreamResources
    }
  }
}
