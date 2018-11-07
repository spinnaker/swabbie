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
import com.netflix.spinnaker.config.CloudProviderConfiguration
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.config.ResourceTypeConfiguration
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.edda.providers.AmazonImagesUsedByInstancesCache
import com.netflix.spinnaker.swabbie.aws.edda.providers.AmazonLaunchConfigurationCache
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AccountExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.AllowListExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.TaskResponse
import com.netflix.spinnaker.swabbie.repository.*
import com.netflix.spinnaker.swabbie.tagging.TaggingService
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.validateMockitoUsage
import org.springframework.context.ApplicationEventPublisher
import java.time.*
import java.util.Optional

object AmazonImageHandlerTest {
  private val front50ApplicationCache = mock<InMemoryCache<Application>>()
  private val accountProvider = mock<AccountProvider>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonImage>>()
  private val clock = Clock.fixed(Instant.parse("2018-05-24T12:34:56Z"), ZoneOffset.UTC)
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val lockingService = Optional.empty<LockingService>()
  private val orcaService = mock<OrcaService>()
  private val imageProvider = mock<ResourceProvider<AmazonImage>>()
  private val instanceProvider = mock<ResourceProvider<AmazonInstance>>()
  private val launchConfigurationProvider = mock<ResourceProvider<AmazonLaunchConfiguration>>()
  private val applicationsCache = mock<InMemoryCache<Application>>()
  private val taggingService = mock<TaggingService>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val swabbieProperties = SwabbieProperties().apply {
    minImagesUsedByInst = 0
    minImagesUsedByLC = 0
  }
  private val launchConfigurationCache = mock<InMemorySingletonCache<AmazonLaunchConfigurationCache>>()
  private val imagesUsedByinstancesCache = mock<InMemorySingletonCache<AmazonImagesUsedByInstancesCache>>()

  private val subject = AmazonImageHandler(
    clock = clock,
    registry = NoopRegistry(),
    notifiers = listOf(mock()),
    rules = listOf(OrphanedImageRule()),
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    exclusionPolicies = listOf(
      LiteralExclusionPolicy(),
      AllowListExclusionPolicy(front50ApplicationCache, accountProvider)
    ),
    resourceOwnerResolver = resourceOwnerResolver,
    applicationEventPublisher = applicationEventPublisher,

    lockingService = lockingService,
    retrySupport = RetrySupport(),
    imageProvider = imageProvider,
    orcaService = orcaService,
    applicationsCaches = listOf(applicationsCache),
    taggingService = taggingService,
    taskTrackingRepository = taskTrackingRepository,
    resourceUseTrackingRepository = resourceUseTrackingRepository,
    swabbieProperties = swabbieProperties,
    launchConfigurationCache = launchConfigurationCache,
    imagesUsedByinstancesCache = imagesUsedByinstancesCache
  )

  @BeforeEach
  fun setup() {
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

    val params = Parameters(account = "1234", region = "us-east-1", environment = "test")
    whenever(imageProvider.getAll(params)) doReturn listOf(
      AmazonImage(
        imageId = "ami-123",
        resourceId = "ami-123",
        description = "ancestor_id=ami-122",
        ownerId = null,
        state = "available",
        resourceType = IMAGE,
        cloudProvider = AWS,
        name = "123-xenial-hvm-sriov-ebs",
        creationDate = LocalDateTime.now().minusDays(3).toString()
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
        creationDate = LocalDateTime.now().minusDays(3).toString()
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
      createdTime = clock.instant().toEpochMilli().minus(Duration.ofDays(3).toMillis())
    )

    whenever(imagesUsedByinstancesCache.get()) doReturn
      AmazonImagesUsedByInstancesCache(
        mapOf(
          "us-east-1" to setOf("ami-132","ami-444")
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
      imageProvider,
      accountProvider,
      instanceProvider,
      launchConfigurationProvider,
      applicationEventPublisher,
      resourceOwnerResolver,
      taskTrackingRepository,
      imagesUsedByinstancesCache,
      launchConfigurationCache
    )
  }

  @Test
  fun `should handle work for images`() {
    Assertions.assertTrue(subject.handles(getWorkConfiguration()))
  }

  @Test
  fun `should find image cleanup candidates`() {
    subject.getCandidates(getWorkConfiguration()).let { images ->
      // we get the entire collection of images
      images!!.size shouldMatch equalTo(2)
    }
  }

  @Test
  fun `should fail to get candidates if checking launch configuration references fails`() {
    val configuration = getWorkConfiguration()
    whenever(launchConfigurationCache.get()) doThrow
      IllegalStateException("launch configs")

    Assertions.assertThrows(IllegalStateException::class.java) {
      subject.preProcessCandidates(subject.getCandidates(getWorkConfiguration()).orEmpty(), configuration)
    }
  }

  @Test
  fun `should fail to get candidates if checking instance references fails`() {
    val configuration = getWorkConfiguration()
    whenever(imagesUsedByinstancesCache.get()) doThrow
      IllegalStateException("failed to get instances")

    Assertions.assertThrows(IllegalStateException::class.java) {
      subject.preProcessCandidates(subject.getCandidates(getWorkConfiguration()).orEmpty(), configuration)
    }
  }

  @Test
  fun `should find cleanup candidates, apply exclusion policies on them and mark them`() {
    val workConfiguration = getWorkConfiguration(
      maxAgeDays = 1,
      exclusionList = mutableListOf(
        Exclusion()
          .withType(ExclusionType.Allowlist.toString())
          .withAttributes(
            listOf(
              Attribute()
                .withKey("imageId")
                .withValue(
                  listOf("ami-123") // will exclude anything else not matching this imageId
                )
            )
          )
      )
    )

    subject.getCandidates(workConfiguration).let { images ->
      // we get the entire collection of images
      images!!.size shouldMatch equalTo(2)
    }

    subject.mark(workConfiguration, postMark = { print("Done") })

    // ami-132 is excluded by exclusion policies, specifically because ami-123 is not allowlisted
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<MarkResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "ami-123")
        Assertions.assertEquals(event.markedResource.projectedDeletionStamp.inDays(), 4)
      }
    )

    verify(resourceRepository, times(1)).upsert(any(), any(), any())
  }

  @Test
  fun `should not mark images referenced by other resources`() {
    whenever(imagesUsedByinstancesCache.get()) doReturn
      AmazonImagesUsedByInstancesCache(
        mapOf(
          "us-east-1" to setOf("ami-123","ami-444")
        ),
        clock.instant().toEpochMilli(),
        "default"
      )

    val workConfiguration = getWorkConfiguration(
      exclusionList = mutableListOf(
        Exclusion()
          .withType(ExclusionType.Allowlist.toString())
          .withAttributes(
            listOf(
              Attribute()
                .withKey("imageId")
                .withValue(
                  listOf("ami-123") // will exclude anything else not matching this imageId
                )
            )
          )
      )
    )

    subject.getCandidates(workConfiguration).let { images ->
      images!!.size shouldMatch equalTo(2)
      Assertions.assertTrue(images.any { it.imageId == "ami-123" })
      Assertions.assertTrue(images.any { it.imageId == "ami-132" })
    }

    subject.mark(workConfiguration) { print { "postMark" } }

    // ami-132 is excluded by exclusion policies, specifically because ami-132 is not allowlisted
    // ami-123 is referenced by an instance, so therefore should not be marked for deletion
    verify(applicationEventPublisher, times(0)).publishEvent(any<MarkResourceEvent>())
    verify(resourceRepository, times(0)).upsert(any(), any(), any())
  }

  @Test
  fun `should not mark ancestor or base images`() {
    val workConfiguration = getWorkConfiguration()
    val params = Parameters(account = "1234", region = "us-east-1", environment = "test")
    whenever(imageProvider.getAll(params)) doReturn listOf(
      AmazonImage(
        imageId = "ami-123",
        resourceId = "ami-123",
        description = "ancestor_id=ami-132",
        ownerId = null,
        state = "available",
        resourceType = IMAGE,
        cloudProvider = AWS,
        name = "123-xenial-hvm-sriov-ebs",
        creationDate = LocalDateTime.now().minusDays(3).toString()
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
        creationDate = LocalDateTime.now().minusDays(3).toString()
      )
    )

    subject.getCandidates(workConfiguration).let { images ->
      images!!.size shouldMatch equalTo(2)
      Assertions.assertTrue(images.any { it.imageId == "ami-123" })
      Assertions.assertTrue(images.any { it.imageId == "ami-132" })
    }

    subject.mark(workConfiguration, { print { "postMark" } })

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
    val thirteenDaysAgo = clock.instant().toEpochMilli() - 13 * 24 * 60 * 60 * 1000L
    val workConfiguration = getWorkConfiguration(maxAgeDays = 2)
    val image = AmazonImage(
      imageId = "ami-123",
      resourceId = "ami-123",
      description = "ancestor_id=ami-122",
      ownerId = null,
      state = "available",
      resourceType = IMAGE,
      cloudProvider = AWS,
      name = "123-xenial-hvm-sriov-ebs",
      creationDate = LocalDateTime.now().minusDays(3).toString()
    )

    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn
      listOf(
        MarkedResource(
          resource = image,
          summaries = listOf(Summary("Image is unused", "testRule 1")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          projectedSoftDeletionStamp = thirteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "Email",
            notificationStamp = fifteenDaysAgo
          )
        )
      )

    val params = Parameters(account = "1234", region = "us-east-1", environment = "test", id = "ami-123")

    whenever(imageProvider.getAll(params)) doReturn listOf(image)
    whenever(orcaService.orchestrate(any())) doReturn TaskResponse(ref = "/tasks/1234")

    subject.delete(workConfiguration) {}

    verify(taskTrackingRepository, times(1)).add(any(), any())
    verify(orcaService, times(1)).orchestrate(any())
  }

  @Test
  fun `should soft delete images`() {
    val fifteenDaysAgo = clock.instant().minusSeconds(15 * 24 * 60 * 60).toEpochMilli()
    val thirteenDaysAgo = clock.instant().minusSeconds(13 * 24 * 60 * 60).toEpochMilli()
    val workConfiguration = getWorkConfiguration(maxAgeDays = 2)
    val image = AmazonImage(
      imageId = "ami-123",
      resourceId = "ami-123",
      description = "ancestor_id=ami-122",
      ownerId = null,
      state = "available",
      resourceType = IMAGE,
      cloudProvider = AWS,
      name = "123-xenial-hvm-sriov-ebs",
      creationDate = LocalDateTime.now().minusDays(3).toString()
    )

    whenever(resourceRepository.getMarkedResourcesToSoftDelete()) doReturn
      listOf(
        MarkedResource(
          resource = image,
          summaries = listOf(Summary("Image is unused", "testRule 1")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          projectedSoftDeletionStamp = thirteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "Email",
            notificationStamp = clock.instant().toEpochMilli()
          )
        )
      )
    val params = Parameters(account = "1234", region = "us-east-1", environment = "test", id = "ami-123")
    whenever(taggingService.upsertImageTag(any())) doReturn "1234"
    whenever(imageProvider.getAll(params)) doReturn listOf(image)

    subject.softDelete(workConfiguration) {}

    verify(taggingService, times(1)).upsertImageTag(any())
    verify(taskTrackingRepository, times(1)).add(any(), any())
  }


  @Test
  fun `should not soft delete if the images have been seen recently`() {
    whenever(resourceUseTrackingRepository.getUnused()) doReturn
      emptyList<LastSeenInfo>()
    whenever(resourceUseTrackingRepository.getUsed()) doReturn setOf("ami-123")

    val fifteenDaysAgo = clock.instant().minusSeconds(15 * 24 * 60 * 60).toEpochMilli()
    val thirteenDaysAgo = clock.instant().minusSeconds(13 * 24 * 60 * 60).toEpochMilli()
    val workConfiguration = getWorkConfiguration(maxAgeDays = 2)
    val image = AmazonImage(
      imageId = "ami-123",
      resourceId = "ami-123",
      description = "ancestor_id=ami-122",
      ownerId = null,
      state = "available",
      resourceType = IMAGE,
      cloudProvider = AWS,
      name = "123-xenial-hvm-sriov-ebs",
      creationDate = LocalDateTime.now().minusDays(3).toString()
    )

    whenever(resourceRepository.getMarkedResourcesToSoftDelete()) doReturn
      listOf(
        MarkedResource(
          resource = image,
          summaries = listOf(Summary("Image is unused", "testRule 1")),
          namespace = workConfiguration.namespace,
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = fifteenDaysAgo,
          projectedSoftDeletionStamp = thirteenDaysAgo,
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "Email",
            notificationStamp = clock.instant().toEpochMilli()
          )
        )
      )
    val params = Parameters(account = "1234", region = "us-east-1", environment = "test", id = "ami-123")
    whenever(taggingService.upsertImageTag(any())) doReturn "1234"
    whenever(imageProvider.getAll(params)) doReturn listOf(image)

    subject.softDelete(workConfiguration) {}

    verify(taggingService, times(0)).upsertImageTag(any())
    verify(taskTrackingRepository, times(0)).add(any(), any())
  }

  private fun Long.inDays(): Int = Duration.between(Instant.ofEpochMilli(clock.millis()), Instant.ofEpochMilli(this)).toDays().toInt()

  private fun getWorkConfiguration(
    isEnabled: Boolean = true,
    dryRunMode: Boolean = false,
    accountIds: List<String> = listOf("test"),
    regions: List<String> = listOf("us-east-1"),
    exclusionList: MutableList<Exclusion> = mutableListOf(),
    maxAgeDays: Int = 1
  ): WorkConfiguration {
    val swabbieProperties = SwabbieProperties().apply {
      dryRun = dryRunMode
      providers = listOf(
        CloudProviderConfiguration().apply {
          name = "aws"
          exclusions = mutableListOf()
          accounts = accountIds
          locations = regions
          resourceTypes = listOf(
            ResourceTypeConfiguration().apply {
              name = "image"
              enabled = isEnabled
              dryRun = dryRunMode
              exclusions = exclusionList
              retention = 2
              softDelete = SoftDelete(true, 2)
              maxAge = maxAgeDays
            }
          )
        }
      )
    }

    return WorkConfigurator(
      swabbieProperties = swabbieProperties,
      accountProvider = accountProvider,
      exclusionPolicies = listOf(AccountExclusionPolicy()),
      exclusionsSuppliers = Optional.empty()
    ).generateWorkConfigurations()[0]
  }
}
