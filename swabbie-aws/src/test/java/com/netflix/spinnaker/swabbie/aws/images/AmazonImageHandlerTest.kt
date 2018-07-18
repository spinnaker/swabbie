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
import com.netflix.spinnaker.config.*
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AccountExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.WhiteListExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.orca.OrcaExecutionStatus
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.TaskDetailResponse
import com.netflix.spinnaker.swabbie.orca.TaskResponse
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.util.*
import org.mockito.Mockito.validateMockitoUsage
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

object AmazonImageHandlerTest {
  private val front50ApplicationCache = mock<InMemoryCache<Application>>()
  private val accountProvider = mock<AccountProvider>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonImage>>()
  private val clock = Clock.systemDefaultZone()
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val lockingService = Optional.empty<LockingService>()
  private val orcaService = mock<OrcaService>()
  private val imageProvider = mock<ResourceProvider<AmazonImage>>()
  private val instanceProvider = mock<ResourceProvider<AmazonInstance>>()
  private val launchConfigurationProvider = mock<ResourceProvider<AmazonLaunchConfiguration>>()
  private val subject = AmazonImageHandler(
    clock = clock,
    registry = NoopRegistry(),
    notifier = mock(),
    rules = listOf(OrphanedImageRule()),
    resourceTrackingRepository = resourceRepository,
    exclusionPolicies = listOf(
      LiteralExclusionPolicy(),
      WhiteListExclusionPolicy(front50ApplicationCache, accountProvider)
    ),
    resourceOwnerResolver = resourceOwnerResolver,
    applicationEventPublisher = applicationEventPublisher,

    lockingService = lockingService,
    retrySupport = RetrySupport(),
    imageProvider = imageProvider,
    instanceProvider = instanceProvider,
    launchConfigurationProvider = launchConfigurationProvider,
    orcaService = orcaService,
    accountProvider = accountProvider
  )

  @BeforeEach
  fun setup() {
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
          eddaEnabled = false
        ),
        SpinnakerAccount(
          name = "prod",
          accountId = "4321",
          type = "aws",
          edda = "http://edda",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false
        )
      )

    val params = Parameters(mapOf("account" to "1234", "region" to "us-east-1"))
    whenever(imageProvider.getAll(params)) doReturn listOf(
      AmazonImage(
        imageId = "ami-123",
        resourceId = "ami-123",
        description = "ancestor_id=ami-123",
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
  }

  @AfterEach
  fun cleanup() {
    validateMockitoUsage()
    reset(imageProvider, accountProvider, instanceProvider, launchConfigurationProvider, applicationEventPublisher)
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
    whenever(launchConfigurationProvider.getAll(
      Parameters(mapOf("account" to "1234", "region" to "us-east-1"))
    )) doThrow IllegalStateException("launch configs")

    Assertions.assertThrows(IllegalStateException::class.java, {
      subject.getCandidates(getWorkConfiguration())
    })
  }

  @Test
  fun `should fail to get candidates if checking instance references fails`() {
    whenever(instanceProvider.getAll(
      Parameters(mapOf("account" to "1234", "region" to "us-east-1"))
    )) doThrow IllegalStateException("failed to get instances")

    Assertions.assertThrows(IllegalStateException::class.java, {
      subject.getCandidates(getWorkConfiguration())
    })
  }

  @Test
  fun `should fail to get candidates if checking for siblings fails because of accounts`() {
    whenever(accountProvider.getAccounts()) doThrow IllegalStateException("failed to get accounts")
    Assertions.assertThrows(IllegalStateException::class.java, {
      subject.getCandidates(getWorkConfiguration())
    })
  }

  @Test
  fun `should fail to get candidates if checking for siblings in other accounts fails`() {
    whenever(imageProvider.getAll(
      Parameters(mapOf("account" to "4321", "region" to "us-east-1"))
    )) doThrow IllegalStateException("failed to get images in 4321/us-east-1")

    Assertions.assertThrows(IllegalStateException::class.java, {
      subject.getCandidates(getWorkConfiguration())
    })
  }

  @Test
  fun `should find cleanup candidates, apply exclusion policies on them and mark them`() {
    val workConfiguration = getWorkConfiguration(
      maxAgeDays = 1,
      exclusionList = mutableListOf(
        Exclusion()
          .withType(ExclusionType.Whitelist.toString())
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

    subject.mark(workConfiguration, postMark= { print("Done") })

    // ami-132 is excluded by exclusion policies, specifically because ami-123 is not whitelisted
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<MarkResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "ami-123")
        Assertions.assertEquals(event.markedResource.projectedDeletionStamp.inDays(), 2)
      }
    )

    verify(resourceRepository, times(1)).upsert(any<MarkedResource>(), any<Long>())
  }

  @Test
  fun `should not mark images referenced by other resources`() {
    val workConfiguration = getWorkConfiguration(
      exclusionList = mutableListOf(
        Exclusion()
          .withType(ExclusionType.Whitelist.toString())
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

    whenever(instanceProvider.getAll(any())) doReturn
      listOf(
        AmazonInstance(
          instanceId = "i-123",
          cloudProvider = AWS,
          imageId = "ami-123", // reference to ami-123
          launchTime = System.currentTimeMillis()
        )
      )

    subject.getCandidates(workConfiguration).let { images ->
      images!!.size shouldMatch equalTo(2)
      Assertions.assertTrue(images.any { it.imageId == "ami-123" })
      Assertions.assertTrue(images.any { it.imageId == "ami-132" })
    }

    subject.mark(workConfiguration, { print {"postMark" } })

    // ami-132 is excluded by exclusion policies, specifically because ami-132 is not whitelisted
    // ami-123 is referenced by an instance, so therefore should not be marked for deletion
    verify(applicationEventPublisher, times(0)).publishEvent(any<MarkResourceEvent>())
    verify(resourceRepository, times(0)).upsert(any<MarkedResource>(), any<Long>())
  }

  @Test
  fun `should delete images`() {
    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    val workConfiguration = getWorkConfiguration(maxAgeDays = 2)
    val image = AmazonImage(
      imageId = "ami-123",
      resourceId = "ami-123",
      description = "ancestor_id=ami-123",
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
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "Email",
            notificationStamp = clock.millis()
          )
        )
      )

    val params = Parameters(
      mapOf("account" to "1234", "region" to "us-east-1", "imageId" to "ami-123")
    )

    whenever(imageProvider.getOne(params)) doReturn listOf(image)
    whenever(orcaService.orchestrate(any())) doReturn TaskResponse(ref = "/tasks/1234")
    whenever(orcaService.getTask("1234")) doReturn
      TaskDetailResponse(
        id = "id",
        application = "app",
        buildTime = "1",
        startTime = "1",
        endTime = "2",
        status = OrcaExecutionStatus.SUCCEEDED,
        name = "delete blah"
      )

    subject.delete(workConfiguration, {})

    verify(resourceRepository, times(1)).remove(any<MarkedResource>())
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<DeleteResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "ami-123")
      }
    )

    verify(orcaService, times(1)).orchestrate(any())
  }

  private fun Long.inDays(): Int
    = Duration.between(Instant.ofEpochMilli(clock.millis()), Instant.ofEpochMilli(this)).toDays().toInt()

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
