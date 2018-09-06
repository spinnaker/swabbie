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
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AllowListExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_PROVIDER_TYPE
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_TYPE
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.*
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.util.*

object ResourceTypeHandlerTest {
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val clock = Clock.systemDefaultZone()
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val lockingService = Optional.empty<LockingService>()
  private val retrySupport = RetrySupport()
  private val ownerResolver = mock<ResourceOwnerResolver<TestResource>>()

  private val postAction: (resource: List<Resource>) -> Unit = {
    println("swabbie post action on $it")
  }

  @BeforeEach
  fun setup() {
    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
  }

  @AfterEach
  fun cleanup() {
    reset(resourceRepository, resourceStateRepository, applicationEventPublisher, ownerResolver)
  }

  @Test
  fun `should track a violating resource`() {
    val resource = TestResource("testResource")
    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = listOf(TestRule(
        invalidOn = { resource.resourceId == "testResource" },
        summary = Summary("violates rule 1", "ruleName"))
      ),
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      exclusionPolicies = listOf(mock()),
      ownerResolver = ownerResolver,
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = mutableListOf(resource),
      notifiers = listOf(mock()),
      lockingService = lockingService,
      retrySupport = retrySupport
    )

    whenever(ownerResolver.resolve(resource)) doReturn "lucious-mayweather@netflix.com"

    handler.mark(workConfiguration = workConfiguration(), postMark = { postAction(listOf(resource)) })

    inOrder(resourceRepository, applicationEventPublisher) {
      verify(resourceRepository).upsert(any(), any())
      verify(applicationEventPublisher).publishEvent(any<MarkResourceEvent>())
    }
  }

  @Test
  fun `should track violating resources`() {
    val resources: List<TestResource> = listOf(
      TestResource("invalid resource 1"),
      TestResource("invalid resource 2"),
      TestResource("valid resource")
    )

    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = resources.map {
        TestRule(
          invalidOn = { it.resourceId == "invalid resource 1" || it.resourceId == "invalid resource 2" },
          summary = Summary(description = "test rule description", ruleName = "test rule name")
        )
      },
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      exclusionPolicies = listOf(mock()),
      ownerResolver = ownerResolver,
      notifiers = listOf(mock()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = resources.toMutableList(),
      lockingService = lockingService,
      retrySupport = retrySupport
    )

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    handler.mark(
      workConfiguration = workConfiguration(),
      postMark = { postAction(resources) }
    )

    verify(resourceRepository, times(2)).upsert(any(), any())
    verify(applicationEventPublisher, times(2)).publishEvent(any<MarkResourceEvent>())
  }

  @Test
  fun `should delete a resource`() {
    val configuration = workConfiguration()
    val candidates = mutableListOf(
      TestResource("1"),
      TestResource("2")
    )

    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn
      listOf(
        MarkedResource(
          resource = TestResource("1"),
          summaries = listOf(Summary("invalid resource 1", "rule 1")),
          namespace = configuration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "yolo@netflix.com",
            notificationType = "Email",
            notificationStamp = clock.millis()
          )
        ),
        MarkedResource(
          resource = TestResource("2"),
          summaries = listOf(Summary("invalid resource 2", "rule 2")),
          namespace = configuration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "yolo@netflix.com",
            notificationType = "Email",
            notificationStamp = clock.millis()
          )
        )
      )

    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = listOf(
        TestRule({ true }, Summary("always invalid", "rule1"))
      ),
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      ownerResolver = ownerResolver,
      exclusionPolicies = listOf(mock()),
      notifiers = listOf(mock()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = candidates,
      lockingService = lockingService,
      retrySupport = retrySupport
    )

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"

    handler.delete(workConfiguration = configuration, postDelete = { postAction(candidates) })

    verify(resourceRepository, times(2)).remove(any())
    candidates.size shouldMatch equalTo(0)
  }

  @Test
  fun `should ignore resource using exclusion strategies`() {
    val resource = TestResource(resourceId = "testResource", name = "testResourceName")
    // configuration with a name exclusion strategy
    val configuration = workConfiguration(exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Literal.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("name")
              .withValue(listOf(resource.name))
          )
        )
    ))

    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = listOf(
        TestRule({ true }, Summary("always invalid", "rule1"))
      ),
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      ownerResolver = ownerResolver,
      exclusionPolicies = listOf(LiteralExclusionPolicy()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = mutableListOf(resource),
      notifiers = listOf(mock()),
      lockingService = lockingService,
      retrySupport = retrySupport
    )

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"

    handler.mark(workConfiguration = configuration, postMark = { postAction(listOf(resource)) })

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should only process resources in allowList`() {
    val resource1 = TestResource("1", name = "allowed resource")
    val resource2 = TestResource("2", name = "not allowed resource")
    // configuration with an allow list exclusion strategy
    val configuration = workConfiguration(exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Allowlist.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("swabbieResourceOwner")
              .withValue(listOf("lucious-mayweather@netflix.com"))
          )
        )
    ))

    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = listOf(
        TestRule({ true }, Summary("always invalid", "rule1"))
      ),
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      ownerResolver = ownerResolver,
      exclusionPolicies = listOf(AllowListExclusionPolicy(mock(), mock())),
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = mutableListOf(resource1, resource2),
      notifiers = listOf(mock()),
      lockingService = lockingService,
      retrySupport = retrySupport
    )

    whenever(ownerResolver.resolve(resource1)) doReturn  "lucious-mayweather@netflix.com, quincy-polaroid@netflix.com"
    whenever(ownerResolver.resolve(resource2)) doReturn  "blah" // excluded because not in allowed list

    handler.mark(workConfiguration = configuration, postMark = { postAction(listOf(resource1, resource2)) })

    verify(applicationEventPublisher, times(1)).publishEvent(
      check<MarkResourceEvent> { event ->
        Assertions.assertTrue((event.markedResource.resourceId == resource1.resourceId))
        Assertions.assertTrue((event.workConfiguration.namespace == configuration.namespace))
      }
    )

    verify(resourceRepository, times(1)).upsert(any(), any())
  }

  @Test
  fun `should ignore opted out resources during delete`() {
    val resource = TestResource(resourceId = "testResource", name = "testResourceName")
    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    val configuration = workConfiguration()
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("invalid resource 2", "rule 2")),
      namespace = configuration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = fifteenDaysAgo,
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflix.com",
        notificationType = "Email",
        notificationStamp = clock.millis()
      )
    )

    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn
      listOf(markedResource)

    whenever(resourceStateRepository.getAll()) doReturn
      listOf(
        ResourceState(
          optedOut = true,
          currentStatus = Status(Action.OPTOUT.name, Instant.now().toEpochMilli()),
          statuses = mutableListOf(
            Status(Action.MARK.name, Instant.now().toEpochMilli()),
            Status(Action.OPTOUT.name, Instant.now().toEpochMilli())
          ),
          markedResource = markedResource
        )
      )

    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = listOf(
        TestRule({ true }, Summary("always invalid", "rule1"))
      ),
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      ownerResolver = ownerResolver,
      exclusionPolicies = listOf(LiteralExclusionPolicy()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = mutableListOf(resource),
      notifiers = listOf(mock()),
      lockingService = lockingService,
      retrySupport = retrySupport
    )

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"

    handler.delete(
      workConfiguration = configuration,
      postDelete = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should ignore opted out resources during mark`() {
    val resource = TestResource(resourceId = "testResource", name = "testResourceName")
    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    val configuration = workConfiguration()
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("invalid resource 2", "rule 2")),
      namespace = configuration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = fifteenDaysAgo,
      notificationInfo = NotificationInfo(
        recipient = "yolo@netflix.com",
        notificationType = "Email",
        notificationStamp = clock.millis()
      )
    )

    whenever(resourceStateRepository.getAll()) doReturn
      listOf(
        ResourceState(
          optedOut = true,
          currentStatus = Status(Action.OPTOUT.name, Instant.now().toEpochMilli()),
          statuses = mutableListOf(
            Status(Action.MARK.name, Instant.now().toEpochMilli()),
            Status(Action.OPTOUT.name, Instant.now().toEpochMilli())
          ),
          markedResource = markedResource
        )
      )

    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = listOf(
        TestRule({ true }, Summary("always invalid", "rule1"))
      ),
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      ownerResolver = ownerResolver,
      exclusionPolicies = listOf(LiteralExclusionPolicy()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = mutableListOf(resource),
      notifiers = listOf(mock()),
      lockingService = lockingService,
      retrySupport = retrySupport
    )

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"

    handler.mark(
      workConfiguration = configuration,
      postMark = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should forget resource if no longer violate a rule`() {
    val resource = TestResource(resourceId = "testResource")
    val configuration = workConfiguration()
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("invalid resource", javaClass.simpleName)),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    whenever(resourceRepository.getMarkedResources()) doReturn
      listOf(markedResource)

    val handler = TestResourceTypeHandler(
      clock = clock,
      rules = listOf(
        TestRule(invalidOn = { true }, summary = null)
      ),
      resourceTrackingRepository = resourceRepository,
      resourceStateRepository = resourceStateRepository,
      ownerResolver = ownerResolver,
      exclusionPolicies = listOf(mock()),
      applicationEventPublisher = applicationEventPublisher,
      simulatedCandidates = mutableListOf(resource),
      notifiers = listOf(mock()),
      lockingService = lockingService,
      retrySupport = mock()
    )

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    handler.mark(
      workConfiguration = configuration,
      postMark = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, times(1)).publishEvent(
      check<UnMarkResourceEvent> { event ->
        Assertions.assertTrue((event.markedResource.resourceId == markedResource.resourceId))
        Assertions.assertTrue((event.workConfiguration.namespace == configuration.namespace))
      }
    )
    verify(resourceRepository, times(1)).remove(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  internal fun workConfiguration(
    exclusions: List<Exclusion> = emptyList(),
    dryRun: Boolean = false
  ): WorkConfiguration = WorkConfiguration(
    namespace = "$TEST_RESOURCE_PROVIDER_TYPE:test:us-east-1:$TEST_RESOURCE_TYPE",
    account = SpinnakerAccount(
      name = "test",
      accountId = "id",
      type = "type",
      edda = "",
      regions = emptyList(),
      eddaEnabled = false
    ),
    location = "us-east-1",
    cloudProvider = TEST_RESOURCE_PROVIDER_TYPE,
    resourceType = TEST_RESOURCE_TYPE,
    retention = 14,
    exclusions = exclusions,
    dryRun = dryRun,
    maxAge = 1
  )

  class TestRule(
    private val invalidOn: (Resource) -> Boolean,
    private val summary: Summary?
  ) : Rule<TestResource> {
    override fun apply(resource: TestResource): Result {
      return if (invalidOn(resource)) Result(summary) else Result(null)
    }
  }

  class TestResourceTypeHandler(
    clock: Clock,
    resourceTrackingRepository: ResourceTrackingRepository,
    resourceStateRepository: ResourceStateRepository,
    ownerResolver: OwnerResolver<TestResource>,
    applicationEventPublisher: ApplicationEventPublisher,
    exclusionPolicies: List<ResourceExclusionPolicy>,
    notifiers: List<Notifier>,
    private val rules: List<Rule<TestResource>>,
    private val simulatedCandidates: MutableList<TestResource>?,
    registry: Registry = NoopRegistry(),
    lockingService: Optional<LockingService>,
    retrySupport: RetrySupport
  ) : AbstractResourceTypeHandler<TestResource>(
    registry,
    clock,
    rules,
    resourceTrackingRepository,
    resourceStateRepository,
    exclusionPolicies,
    ownerResolver,
    notifiers,
    applicationEventPublisher,
    lockingService,
    retrySupport
  ) {
    override fun deleteResources(
      markedResources: List<MarkedResource>,
      workConfiguration: WorkConfiguration
    ): ReceiveChannel<MarkedResource> = produce {
      markedResources.forEach { m ->
        simulatedCandidates
          ?.removeIf { r -> m.resourceId == r.resourceId }.also {
            if (it != null && it) {
              send(m)
            }
          }
      }
    }

    // simulates querying for a resource upstream
    override fun getCandidate(
      markedResource: MarkedResource,
      workConfiguration: WorkConfiguration
    ): TestResource? {
      return simulatedCandidates?.find { markedResource.resourceId == it.resourceId }
    }

    override fun preProcessCandidates(
      candidates: List<TestResource>,
      workConfiguration: WorkConfiguration
    ): List<TestResource> {
      log.debug("pre-processing test resources {}", candidates)
      return candidates
    }

    override fun handles(workConfiguration: WorkConfiguration): Boolean {
      return !rules.isEmpty()
    }

    override fun getCandidates(workConfiguration: WorkConfiguration): List<TestResource>? {
      return simulatedCandidates
    }
  }
}
