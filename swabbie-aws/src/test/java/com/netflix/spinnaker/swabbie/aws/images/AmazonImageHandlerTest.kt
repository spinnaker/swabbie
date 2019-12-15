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

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.InMemorySingletonCache
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.caches.AmazonImagesUsedByInstancesCache
import com.netflix.spinnaker.swabbie.aws.caches.AmazonLaunchConfigurationCache
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.TaskResponse
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import com.netflix.spinnaker.swabbie.rules.ResourceRulesEngine
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import com.nhaarman.mockito_kotlin.any
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
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

object AmazonImageHandlerTest {
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
  private val rulesEngine = mock<ResourceRulesEngine>()
  private val ruleAndViolationPair = Pair<Rule, List<Summary>>(mock(), listOf(Summary("violate rule", ruleName = "rule")))
  private val workConfiguration = WorkConfigurationTestHelper
    .generateWorkConfiguration(resourceType = IMAGE, cloudProvider = AWS)

  private val params = Parameters(
    account = workConfiguration.account.accountId!!,
    region = workConfiguration.location,
    environment = workConfiguration.account.environment
  )

  private val usedByInstancesCache = AmazonImagesUsedByInstancesCache(emptyMap(), clock.millis(), "instanceCache")
  private val usedByLaunchConfigCache = AmazonLaunchConfigurationCache(emptyMap(), clock.millis(), "launchConfigCache")

  private val subject = AmazonImageHandler(
    clock = clock,
    registry = NoopRegistry(),
    notifier = mock(),
    rulesEngine = rulesEngine,
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    exclusionPolicies = listOf(),
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

  private const val user = "test@netflix.com"
  private val ami123 = AmazonImage(
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

  private val ami132 = AmazonImage(
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

  @BeforeEach
  fun setup() {
    ami123.details.clear()
    ami132.details.clear()

    whenever(resourceOwnerResolver.resolve(any())) doReturn user
    whenever(aws.getImages(params)) doReturn listOf(ami123, ami132)
    whenever(imagesUsedByinstancesCache.get()) doReturn usedByInstancesCache
    whenever(launchConfigurationCache.get()) doReturn usedByLaunchConfigCache

    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle
  }

  @AfterEach
  fun cleanup() {
    validateMockitoUsage()
    reset(
      resourceRepository,
      aws,
      applicationEventPublisher,
      resourceOwnerResolver,
      taskTrackingRepository,
      imagesUsedByinstancesCache,
      launchConfigurationCache,
      rulesEngine
    )
  }

  @Test
  fun `should handle images`() {
    whenever(rulesEngine.getRules(workConfiguration)) doReturn listOf(ruleAndViolationPair.first)
    expectThat(subject.handles(workConfiguration)).isTrue()

    whenever(rulesEngine.getRules(workConfiguration)) doReturn emptyList<Rule>()
    expectThat(subject.handles(workConfiguration)).isFalse()
  }

  @Test
  fun `should get images`() {
    expectThat(subject.getCandidates(workConfiguration)!!.count()).isEqualTo(2)
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
  fun `should mark images`() {
    whenever(rulesEngine.evaluate(any<AmazonImage>(), any())) doReturn ruleAndViolationPair.second

    subject.mark(workConfiguration)

    verify(resourceRepository, times(2)).upsert(any(), any())
    verify(applicationEventPublisher, times(2)).publishEvent(any<MarkResourceEvent>())
    verifyNoMoreInteractions(applicationEventPublisher)
  }

  @Test
  fun `should set used by instances`() {
    whenever(rulesEngine.evaluate(eq(ami123), any())) doReturn ruleAndViolationPair.second
    whenever(imagesUsedByinstancesCache.get()) doReturn
      AmazonImagesUsedByInstancesCache(
        mapOf(
          "us-east-1" to setOf(ami123.imageId, "ami-444")
        ),
        clock.millis(),
        "default"
      )

    expectThat(ami123.details[USED_BY_INSTANCES]).isNull()
    subject.preProcessCandidates(listOf(ami123, ami132), workConfiguration)

    expectThat(ami123.details[USED_BY_INSTANCES]).isEqualTo(true)
    expectThat(ami132.details[USED_BY_INSTANCES]).isNull()
  }

  @Test
  fun `should delete images`() {
    val markedResources = listOf(
      MarkedResource(
        resource = ami123,
        summaries = ruleAndViolationPair.second,
        namespace = workConfiguration.namespace,
        resourceOwner = user,
        projectedDeletionStamp = clock.millis(),
        notificationInfo = NotificationInfo(
          recipient = user,
          notificationType = "email",
          notificationStamp = clock.millis()
        )
      ))

    whenever(rulesEngine.evaluate(any<AmazonImage>(), any())) doReturn ruleAndViolationPair.second
    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn markedResources

    whenever(aws.getImages(params.copy(id = ami123.imageId))) doReturn listOf(ami123)
    whenever(orcaService.orchestrate(any())) doReturn TaskResponse(ref = "/tasks/1234")

    subject.delete(workConfiguration)

    verify(orcaService).orchestrate(any())
    verify(taskTrackingRepository).add(any(), any())
    verify(applicationEventPublisher).publishEvent(any<DeleteResourceEvent>())

    verifyNoMoreInteractions(applicationEventPublisher)
    verifyNoMoreInteractions(orcaService)
  }
}
