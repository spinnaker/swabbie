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
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AllowListExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.repository.*
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_PROVIDER_TYPE
import com.netflix.spinnaker.swabbie.test.TEST_RESOURCE_TYPE
import com.netflix.spinnaker.swabbie.test.TestResource
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

object ResourceTypeHandlerTest {
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val clock = Clock.fixed(Instant.parse("2018-11-26T09:30:00Z"), ZoneOffset.UTC)
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val lockingService = Optional.empty<LockingService>()
  private val retrySupport = RetrySupport()
  private val ownerResolver = mock<ResourceOwnerResolver<TestResource>>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val dynamicConfigService = mock<DynamicConfigService>()

  private val postAction: (resource: List<Resource>) -> Unit = {
    println("swabbie post action on $it")
  }

  private val defaultRules = mutableListOf(TestRule(
    invalidOn = { defaultResource.resourceId == "testResource" },
    summary = Summary("violates rule 1", "ruleName"))
  )

  private val alwaysInvalidRules = mutableListOf(
    TestRule({ true }, Summary("always invalid", "rule1"))
  )

  private val alwaysValidRules = mutableListOf(
    TestRule(invalidOn = { true }, summary = null)
  )

  private val defaultResource = TestResource(resourceId = "testResource", name = "testResourceName")

  //must update rules and candidates before using
  private val defaultHandler = TestResourceTypeHandler(
    clock = clock,
    rules = mutableListOf(),
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    exclusionPolicies = mutableListOf(mock()),
    ownerResolver = ownerResolver,
    applicationEventPublisher = applicationEventPublisher,
    simulatedCandidates = mutableListOf(),
    notifiers = listOf(mock()),
    lockingService = lockingService,
    retrySupport = retrySupport,
    taskTrackingRepository = taskTrackingRepository,
    resourceUseTrackingRepository = resourceUseTrackingRepository,
    dynamicConfigService = dynamicConfigService
  )


  @BeforeEach
  fun setup() {
    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
  }

  @AfterEach
  fun cleanup() {
    reset(resourceRepository, resourceStateRepository, applicationEventPublisher, ownerResolver, taskTrackingRepository)
    defaultHandler.clearCandidates()
    defaultHandler.clearRules()
    defaultHandler.clearExclusionPolicies()
  }

  @Test
  fun `should track a violating resource`() {
    whenever(ownerResolver.resolve(defaultResource)) doReturn "lucious-mayweather@netflix.com"
    defaultHandler.setCandidates(mutableListOf(defaultResource))
    defaultHandler.setRules(defaultRules)

    defaultHandler.mark(workConfiguration = workConfiguration(), postMark = { postAction(listOf(defaultResource)) })

    inOrder(resourceRepository, applicationEventPublisher) {
      verify(resourceRepository).upsert(any(), any())
      verify(applicationEventPublisher).publishEvent(any<MarkResourceEvent>())
    }
  }

  @Test
  fun `should track violating resources`() {
    val resources: MutableList<TestResource> = mutableListOf(
      TestResource("invalid resource 1"),
      TestResource("invalid resource 2"),
      TestResource("valid resource")
    )

    defaultHandler.setCandidates(resources)
    defaultHandler.setRules(resources.map {
      TestRule(
        invalidOn = { it.resourceId == "invalid resource 1" || it.resourceId == "invalid resource 2" },
        summary = Summary(description = "test rule description", ruleName = "test rule name")
      )
    }.toMutableList())

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    defaultHandler.mark(
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

    defaultHandler.setRules(alwaysInvalidRules)
    defaultHandler.setCandidates(candidates)

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

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"

    defaultHandler.delete(workConfiguration = configuration, postDelete = { postAction(candidates) })

    verify(taskTrackingRepository, times(2)).add(any(), any())
    candidates.size shouldMatch equalTo(0)
  }

  @Test
  fun `should ignore resource using exclusion strategies`() {
    // configuration with a name exclusion strategy
    val configuration = workConfiguration(exclusions = listOf(
      Exclusion()
        .withType(ExclusionType.Literal.toString())
        .withAttributes(
          listOf(
            Attribute()
              .withKey("name")
              .withValue(listOf(defaultResource.name))
          )
        )
    ))

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    defaultHandler.setRules(alwaysInvalidRules)

    defaultHandler.mark(workConfiguration = configuration, postMark = { postAction(listOf(defaultResource)) })

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
    defaultHandler.setRules(alwaysInvalidRules)
    defaultHandler.setCandidates(mutableListOf(resource1, resource2))
    defaultHandler.setExclusionPolicies(mutableListOf(AllowListExclusionPolicy(mock(), mock())))


    whenever(ownerResolver.resolve(resource1)) doReturn "lucious-mayweather@netflix.com, quincy-polaroid@netflix.com"
    whenever(ownerResolver.resolve(resource2)) doReturn "blah" // excluded because not in allowed list

    defaultHandler.mark(workConfiguration = configuration, postMark = { postAction(listOf(resource1, resource2)) })

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

    defaultHandler.setRules(alwaysInvalidRules)

    defaultHandler.delete(workConfiguration = configuration, postDelete = { postAction(listOf(resource)) })

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should ignore opted out resources during mark`() {
    val resource = TestResource(resourceId = "testResource", name = "testResourceName")
    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    val workConfiguration = workConfiguration()
    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("invalid resource 2", "rule 2")),
      namespace = workConfiguration.namespace,
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

    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle
    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"

    defaultHandler.setRules(alwaysInvalidRules)

    defaultHandler.mark(
      workConfiguration = workConfiguration,
      postMark = { postAction(listOf(resource)) }
    )

    verify(applicationEventPublisher, never()).publishEvent(any())
    verify(resourceRepository, never()).upsert(any(), any())
  }

  @Test
  fun `should partition the list of resources to delete by grouping`() {
    // resources 1 & 2 would be grouped into a partition because their names match to the same app
    // resource 3 would be in its own partition
    val resource1 = TestResource("1", name = "testResource-v001", grouping = Grouping("testResource", GroupingType.APPLICATION))
    val resource2 = TestResource("2", name = "testResource-v002", grouping = Grouping("testResource", GroupingType.APPLICATION))
    val resource3 = TestResource("3", name = "random")
    val resource4 = TestResource(
      resourceId = "4",
      name = "my-package-0.0.2",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME)
    )
    val resource5 = TestResource(
      resourceId = "5",
      name = "my-package-0.0.4",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME)
    )

    val configuration = workConfiguration(
      itemsProcessedBatchSize = 2,
      maxItemsProcessedPerCycle = 3
    )

    val markedResources = listOf(
      MarkedResource(
        resource = resource1,
        summaries = listOf(Summary("invalid resource 1", "rule 1")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource2,
        summaries = listOf(Summary("invalid resource 2", "rule 2")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource3,
        summaries = listOf(Summary("invalid resource random", "rule 3")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource4,
        summaries = listOf(Summary("invalid resource random", "rule 4")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource5,
        summaries = listOf(Summary("invalid resource random", "rule 5")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      )
    )

    defaultHandler.setRules(alwaysInvalidRules)
    defaultHandler.setCandidates(mutableListOf(resource1, resource2, resource3))

    val result = defaultHandler.partitionList(markedResources, configuration)
    Assertions.assertTrue(result.size == 3)
    Assertions.assertTrue(result[0].size == 2, "resources 1 & 2 because their names match to the same app")
    Assertions.assertTrue(result[1].size == 2)
    Assertions.assertTrue(result[2].size == 1)
    Assertions.assertTrue(result[0].none { it.name == resource3.name })
    Assertions.assertTrue(result[1].all { it.name!!.startsWith("my-package") })
    Assertions.assertTrue(result[2].all { it.name == resource3.name })
  }

  @Test
  fun `partition same owner different grouping should be 2 lists`() {
    val resource1 = TestResource(
      resourceId = "4",
      name = "my-other-package-0.0.2",
      resourceType = "image",
      grouping = Grouping("my-other-package", GroupingType.PACKAGE_NAME)
    )
    val resource2 = TestResource(
      resourceId = "5",
      name = "my-package-0.0.4",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME)
    )

    val configuration = workConfiguration(
      itemsProcessedBatchSize = 2,
      maxItemsProcessedPerCycle = 3
    )

    val markedResources = listOf(
      MarkedResource(
        resource = resource1,
        summaries = listOf(Summary("invalid resource 1", "rule 1")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      ),
      MarkedResource(
        resource = resource2,
        summaries = listOf(Summary("invalid resource 2", "rule 2")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = clock.millis()
      )
    )

    defaultHandler.setRules(alwaysInvalidRules)
    defaultHandler.setCandidates(mutableListOf(resource1, resource2))

    val result = defaultHandler.partitionList(markedResources, configuration)
    Assertions.assertTrue(result.size == 2)
    Assertions.assertTrue(result[0].size == 1)
    Assertions.assertTrue(result[1].size == 1)
    Assertions.assertTrue(result[0].none { it.name == resource2.name })
    Assertions.assertTrue(result[1].none { it.name == resource1.name })
  }

  @Test
  fun `should forget resource if no longer violate a rule`() {
    val configuration = workConfiguration()
    val markedResource = MarkedResource(
      resource = defaultResource,
      summaries = listOf(Summary("invalid resource", javaClass.simpleName)),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    whenever(resourceRepository.getMarkedResources())
      .thenReturn(listOf(markedResource))
      .thenReturn(emptyList())

    defaultHandler.setRules(alwaysValidRules)
    defaultHandler.setCandidates(mutableListOf(defaultResource))

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    defaultHandler.mark(
      workConfiguration = configuration,
      postMark = { postAction(listOf(defaultResource)) }
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

  @Test
  fun `should forget resource if it is excluded after being marked`() {
    val configuration = workConfiguration()
    val markedResource = MarkedResource(
      resource = defaultResource,
      summaries = listOf(Summary("invalid resource", javaClass.simpleName)),
      namespace = configuration.namespace,
      projectedDeletionStamp = clock.millis()
    )

    whenever(resourceRepository.getMarkedResources())
      .thenReturn(emptyList())
      .thenReturn(listOf(markedResource))


    defaultHandler.setRules(alwaysValidRules)
    defaultHandler.setCandidates(mutableListOf(defaultResource))

    whenever(ownerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    defaultHandler.mark(
      workConfiguration = configuration,
      postMark = { postAction(listOf(defaultResource)) }
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

  @Test
  fun `delete time should equal now plus retention days`() {
    val configuration = workConfiguration(retention = 2)
    val plus2Days = Instant.parse("2018-11-28T09:30:00Z").toEpochMilli()
    defaultHandler.deletionTimestamp(configuration) shouldMatch equalTo(plus2Days)
  }

  @Test
  fun `update delete timestamp should work`() {
    val timestamp10days = clock.millis().plus(TimeUnit.DAYS.toMillis(10))
    val timestamp1hour = clock.millis().plus(TimeUnit.HOURS.toMillis(1))
    val resource1 = TestResource(
      "1",
      name = "testResource-v001",
      grouping = Grouping("testResource", GroupingType.APPLICATION),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(11))
    )
    val resource2 = TestResource(
      "2",
      name = "testResource-v002",
      grouping = Grouping("testResource", GroupingType.APPLICATION),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10))
    )
    val resource3 = TestResource("3", name = "random", createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10)))
    val resource4 = TestResource(
      resourceId = "4",
      name = "my-package-0.0.2",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10))
    )
    val resource5 = TestResource(
      resourceId = "5",
      name = "my-package-0.0.4",
      resourceType = "image",
      grouping = Grouping("my-package", GroupingType.PACKAGE_NAME),
      createTs = clock.millis().minus(TimeUnit.DAYS.toMillis(10))
    )

    val configuration = workConfiguration(
      itemsProcessedBatchSize = 2,
      maxItemsProcessedPerCycle = 3
    )

    val markedResource1 = MarkedResource(
      resource = resource1,
      summaries = listOf(Summary("invalid resource 1", "rule 1")),
      namespace = configuration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = timestamp10days
    )

    val updatedMarkedResource1 = MarkedResource(
      resource = resource1,
      summaries = listOf(Summary("invalid resource 1", "rule 1")),
      namespace = configuration.namespace,
      resourceOwner = "test@netflix.com",
      projectedDeletionStamp = timestamp1hour
    )

    val markedResources = listOf(
      markedResource1,
      MarkedResource(
        resource = resource2,
        summaries = listOf(Summary("invalid resource 2", "rule 2")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      ),
      MarkedResource(
        resource = resource3,
        summaries = listOf(Summary("invalid resource random", "rule 3")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      ),
      MarkedResource(
        resource = resource4,
        summaries = listOf(Summary("invalid resource random", "rule 4")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      ),
      MarkedResource(
        resource = resource5,
        summaries = listOf(Summary("invalid resource random", "rule 5")),
        namespace = configuration.namespace,
        resourceOwner = "test@netflix.com",
        projectedDeletionStamp = timestamp10days
      )
    )

    whenever(resourceRepository.getMarkedResources()) doReturn markedResources

    defaultHandler.setRules(alwaysValidRules)
    defaultHandler.setCandidates(mutableListOf(defaultResource))

    defaultHandler.recalculateDeletionTimestamp(configuration.namespace, 3600, 1)
    verify(resourceRepository, times(1)).upsert(updatedMarkedResource1)

  }

  internal fun workConfiguration(
    exclusions: List<Exclusion> = emptyList(),
    dryRun: Boolean = false,
    itemsProcessedBatchSize: Int = 1,
    maxItemsProcessedPerCycle: Int = 10,
    retention: Int = 14
  ): WorkConfiguration = WorkConfiguration(
    namespace = "$TEST_RESOURCE_PROVIDER_TYPE:test:us-east-1:$TEST_RESOURCE_TYPE",
    account = SpinnakerAccount(
      name = "test",
      accountId = "id",
      type = "type",
      edda = "",
      regions = emptyList(),
      eddaEnabled = false,
      environment = "test"
    ),
    location = "us-east-1",
    cloudProvider = TEST_RESOURCE_PROVIDER_TYPE,
    resourceType = TEST_RESOURCE_TYPE,
    retention = retention,
    exclusions = exclusions,
    dryRun = dryRun,
    maxAge = 1,
    itemsProcessedBatchSize = itemsProcessedBatchSize,
    maxItemsProcessedPerCycle = maxItemsProcessedPerCycle
  )
}
