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
package com.netflix.spinnaker.swabbie.aws.images

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleConfiguration
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.InMemorySingletonCache
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.caches.AmazonImagesUsedByInstancesCache
import com.netflix.spinnaker.swabbie.aws.caches.AmazonLaunchConfigurationCache
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AllowListExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.Region
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.TaskResponse
import com.netflix.spinnaker.swabbie.repository.LastSeenInfo
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import com.netflix.spinnaker.swabbie.rules.ResourceRulesEngine
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.check
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.doThrow
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.validateMockitoUsage
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// TODO: jeyrs these tests should not care about what rules to include, the specific rules should have coverage for that
// TODO: jeyrs Handler's tests should only care about the api given a resource is valid or not. Just need a mock for RulesEngine
object AmazonImageHandlerTest {
  private val front50ApplicationCache = mock<InMemoryCache<Application>>()
  private val accountProvider = mock<AccountProvider>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val usedSnapshotRepository = mock<UsedResourceRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonImage>>()
  private val clock = Clock.fixed(Instant.parse("2018-05-24T12:34:56Z"), ZoneOffset.UTC)
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val orcaService = mock<OrcaService>()
  private val aws = mock<AWS>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val swabbieProperties = SwabbieProperties().apply {
    minImagesUsedByInst = 0
    minImagesUsedByLC = 0
  }
  private val launchConfigurationCache = mock<InMemorySingletonCache<AmazonLaunchConfigurationCache>>()
  private val imagesUsedByinstancesCache = mock<InMemorySingletonCache<AmazonImagesUsedByInstancesCache>>()
  private val applicationUtils = ApplicationUtils(emptyList())
  private val dynamicConfigService = mock<DynamicConfigService>()
  private val notificationQueue = mock<NotificationQueue>()
  private val imageRules = listOf(OrphanedImageRule())
  private val enabledRules = setOf(
    RuleConfiguration()
      .apply {
        operator = RuleConfiguration.OPERATOR.OR
        rules = imageRules.map { r ->
          RuleDefinition()
            .apply { name = r.name() }
        }.toSet()
      })

  private val workConfiguration = WorkConfigurationTestHelper
    .generateWorkConfiguration(resourceType = IMAGE, cloudProvider = AWS)
    .copy(enabledRules = enabledRules)

  private val params = Parameters(
    account = workConfiguration.account.accountId!!,
    region = workConfiguration.location,
    environment = workConfiguration.account.environment
  )

  private val subject = AmazonImageHandler(
    clock = clock,
    registry = NoopRegistry(),
    notifier = mock(),
    rulesEngine = ResourceRulesEngine(imageRules, listOf(workConfiguration)),
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    exclusionPolicies = listOf(
      LiteralExclusionPolicy(),
      AllowListExclusionPolicy(front50ApplicationCache, accountProvider)
    ),
    resourceOwnerResolver = resourceOwnerResolver,
    applicationEventPublisher = applicationEventPublisher,
    aws = aws,
    orcaService = orcaService,
    taskTrackingRepository = taskTrackingRepository,
    resourceUseTrackingRepository = resourceUseTrackingRepository,
    swabbieProperties = swabbieProperties,
    launchConfigurationCache = launchConfigurationCache,
    imagesUsedByinstancesCache = imagesUsedByinstancesCache,
    applicationUtils = applicationUtils,
    dynamicConfigService = dynamicConfigService,
    usedResourceRepository = usedSnapshotRepository,
    notificationQueue = notificationQueue
  )

  @BeforeEach
  fun setup() {
    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle
    whenever(resourceOwnerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
    whenever(front50ApplicationCache.get()) doReturn
      setOf(
        Application(name = "testapp", email = "name@netflix.com"),
        Application(name = "important", email = "test@netflix.com"),
        Application(name = "random", email = "random@netflix.com")
      )

    whenever(accountProvider.getAccounts()) doReturn
      setOf(
        SpinnakerAccount(
          name = "test",
          accountId = "1234",
          type = "aws",
          edda = "http://edda",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false,
          environment = "test"
        ),
        SpinnakerAccount(
          name = "prod",
          accountId = "4321",
          type = "aws",
          edda = "http://edda",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false,
          environment = "prod"
        )
      )

    whenever(aws.getImages(params)) doReturn listOf(
      AmazonImage(
        imageId = "ami-123",
        resourceId = "ami-123",
        description = "ancestor_id=ami-122",
        ownerId = null,
        state = "available",
        resourceType = IMAGE,
        cloudProvider = AWS,
        name = "123-xenial-hvm-sriov-ebs",
        creationDate = LocalDateTime.now(clock).minusDays(3).toString(),
        blockDeviceMappings = emptyList()
      ),
      AmazonImage(
        imageId = "ami-132",
        resourceId = "ami-132",
        description = "description 132",
        ownerId = null,
        state = "available",
        resourceType = IMAGE,
        cloudProvider = AWS,
        name = "132-xenial-hvm-sriov-ebs",
        creationDate = LocalDateTime.now(clock).minusDays(3).toString(),
        blockDeviceMappings = emptyList()
      )
    )

    whenever(resourceUseTrackingRepository.getUnused()) doReturn
      listOf(
        LastSeenInfo(
          "ami-123",
          "sg-123-v001",
          clock.instant().minus(Duration.ofDays(32)).toEpochMilli()
        ),
        LastSeenInfo(
          "ami-132",
          "sg-132-v001",
          clock.instant().minus(Duration.ofDays(32)).toEpochMilli()
        )
      )

    val defaultLaunchConfig = AmazonLaunchConfiguration(
      name = "test-app-v001-111920",
      launchConfigurationName = "test-app-v001-111920",
      imageId = "ami-132",
      createdTime = clock.instant().minus(Duration.ofDays(3)).toEpochMilli()
    )

    whenever(imagesUsedByinstancesCache.get()) doReturn
      AmazonImagesUsedByInstancesCache(
        mapOf(
          "us-east-1" to setOf("ami-132", "ami-444")
        ),
        clock.instant().toEpochMilli(),
        "default"
      )
    whenever(launchConfigurationCache.get()) doReturn
      AmazonLaunchConfigurationCache(
        mapOf(
          "us-east-1" to mapOf("ami-132" to setOf(defaultLaunchConfig))
        ),
        clock.instant().toEpochMilli(), "default"
      )
  }

  @AfterEach
  fun cleanup() {
    validateMockitoUsage()
    reset(
      resourceRepository,
      aws,
      accountProvider,
      applicationEventPublisher,
      resourceOwnerResolver,
      taskTrackingRepository,
      imagesUsedByinstancesCache,
      launchConfigurationCache
    )
  }

  @Test
  fun `should handle work for images`() {
    expectThat(subject.handles(workConfiguration))
  }

  @Test
  fun `should find image cleanup candidates`() {
    subject.getCandidates(workConfiguration).let { images ->
      // we get the entire collection of images
      images!!.size shouldMatch equalTo(2)
    }
  }

  @Test
  fun `should fail to get candidates if checking launch configuration references fails`() {
    whenever(launchConfigurationCache.get()) doThrow
      IllegalStateException("launch configs")

    Assertions.assertThrows(IllegalStateException::class.java) {
      subject.preProcessCandidates(subject.getCandidates(workConfiguration).orEmpty(), workConfiguration)
    }
  }

  @Test
  fun `should fail to get candidates if checking instance references fails`() {
    whenever(imagesUsedByinstancesCache.get()) doThrow
      IllegalStateException("failed to get instances")

    Assertions.assertThrows(IllegalStateException::class.java) {
      subject.preProcessCandidates(subject.getCandidates(workConfiguration).orEmpty(), workConfiguration)
    }
  }

  @Test
  fun `should find cleanup candidates, apply exclusion policies on them and mark them`() {
    val allowList = Exclusion()
      .withType(ExclusionType.Allowlist.toString())
      .withAttributes(
        setOf(
          Attribute()
            .withKey("imageId")
            .withValue(listOf("ami-123"))
        ))

    val workConfiguration = workConfiguration.copy(maxAge = 1, exclusions = setOf(allowList), retention = 2)
    subject.getCandidates(workConfiguration).let { images ->
      // we get the entire collection of images
      images!!.size shouldMatch equalTo(2)
    }

    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle

    subject.mark(workConfiguration.copy(enabledRules = enabledRules))

    // ami-132 is excluded by exclusion policies, specifically because ami-123 is not allowlisted
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<MarkResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "ami-123")
        Assertions.assertEquals(event.markedResource.projectedDeletionStamp.inDays(), 4)
      }
    )

    verify(resourceRepository, times(1)).upsert(any(), any())
  }

  @Test
  fun `should not mark images referenced by other resources`() {
    val allowList = Exclusion()
      .withType(ExclusionType.Allowlist.toString())
      .withAttributes(
        setOf(
          Attribute()
            .withKey("imageId")
            .withValue(listOf("ami-123"))
        ))

    val workConfiguration = workConfiguration.copy(exclusions = setOf(allowList), retention = 2)
    whenever(imagesUsedByinstancesCache.get()) doReturn
      AmazonImagesUsedByInstancesCache(
        mapOf(
          "us-east-1" to setOf("ami-123", "ami-444")
        ),
        clock.instant().toEpochMilli(),
        "default"
      )

    subject.getCandidates(workConfiguration).let { images ->
      images!!.size shouldMatch equalTo(2)
      Assertions.assertTrue(images.any { it.imageId == "ami-123" })
      Assertions.assertTrue(images.any { it.imageId == "ami-132" })
    }

    subject.mark(workConfiguration)

    // ami-132 is excluded by exclusion policies, specifically because ami-132 is not allowlisted
    // ami-123 is referenced by an instance, so therefore should not be marked for deletion
    verify(applicationEventPublisher, times(0)).publishEvent(any<MarkResourceEvent>())
    verify(resourceRepository, times(0)).upsert(any(), any())
  }

  @Test
  fun `should not mark ancestor or base images`() {
    whenever(aws.getImages(params)) doReturn listOf(
      AmazonImage(
        imageId = "ami-123",
        resourceId = "ami-123",
        description = "ancestor_id=ami-132",
        ownerId = null,
        state = "available",
        resourceType = IMAGE,
        cloudProvider = AWS,
        name = "123-xenial-hvm-sriov-ebs",
        creationDate = LocalDateTime.now(clock).minusDays(3).toString(),
        blockDeviceMappings = emptyList()
      ),
      AmazonImage(
        imageId = "ami-132",
        resourceId = "ami-132",
        description = "description 132",
        ownerId = null,
        state = "available",
        resourceType = IMAGE,
        cloudProvider = AWS,
        name = "132-xenial-hvm-sriov-ebs",
        creationDate = LocalDateTime.now().minusDays(3).toString(),
        blockDeviceMappings = emptyList()
      )
    )

    subject.getCandidates(workConfiguration).let { images ->
      images!!.size shouldMatch equalTo(2)
      Assertions.assertTrue(images.any { it.imageId == "ami-123" })
      Assertions.assertTrue(images.any { it.imageId == "ami-132" })
    }

    subject.mark(workConfiguration)

    // ami-132 is an ancestor/base for ami-123 so skip that
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<MarkResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "ami-123")
      }
    )
  }

  @Test
  fun `should delete images`() {
    val fifteenDaysAgo = clock.instant().toEpochMilli() - 15 * 24 * 60 * 60 * 1000L
    val workConfiguration = workConfiguration.copy(maxAge = 2)
    val image = AmazonImage(
      imageId = "ami-123",
      resourceId = "ami-123",
      description = "ancestor_id=ami-122",
      ownerId = null,
      state = "available",
      resourceType = IMAGE,
      cloudProvider = AWS,
      name = "123-xenial-hvm-sriov-ebs",
      creationDate = LocalDateTime.now(clock).minusDays(3).toString(),
      blockDeviceMappings = emptyList()
    )

    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn
      listOf(
        MarkedResource(
          resource = image,
          summaries = listOf(Summary("Image is unused", "testRule 1")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "email",
            notificationStamp = fifteenDaysAgo
          )
        )
      )

    whenever(aws.getImages(params.copy(id = "ami-123"))) doReturn listOf(image)
    whenever(orcaService.orchestrate(any())) doReturn TaskResponse(ref = "/tasks/1234")

    subject.delete(workConfiguration)

    verify(taskTrackingRepository, times(1)).add(any(), any())
    verify(orcaService, times(1)).orchestrate(any())
  }

  private fun Long.inDays(): Int = Duration.between(Instant.ofEpochMilli(clock.millis()), Instant.ofEpochMilli(this)).toDays().toInt()
}
