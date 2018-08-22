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

package com.netflix.spinnaker.swabbie.aws.autoscalinggroups

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
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.ResourceProvider
import com.netflix.spinnaker.swabbie.ResourceStateRepository
import com.netflix.spinnaker.swabbie.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.WorkConfigurator
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AccountExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.AllowListExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.Region
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.orca.OrcaExecutionStatus
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.TaskDetailResponse
import com.netflix.spinnaker.swabbie.orca.TaskResponse
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.check
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.validateMockitoUsage
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional

object AmazonAutoScalingGroupHandlerTest {
  private val front50ApplicationCache = mock<InMemoryCache<Application>>()
  private val accountProvider = mock<AccountProvider>()
  private val serverGroupProvider = mock<ResourceProvider<AmazonAutoScalingGroup>>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonAutoScalingGroup>>()
  private val clock = Clock.systemDefaultZone()
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val lockingService = Optional.empty<LockingService>()
  private val orcaService = mock<OrcaService>()

  private val subject = AmazonAutoScalingGroupHandler(
    clock = clock,
    registry = NoopRegistry(),
    notifiers = listOf(mock()),
    rules = listOf(ZeroInstanceDisabledServerGroupRule()),
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
    serverGroupProvider = serverGroupProvider,
    orcaService = orcaService
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
  }

  @AfterEach
  fun cleanup() {
    validateMockitoUsage()
    reset(serverGroupProvider, accountProvider, applicationEventPublisher)
  }

  @Test
  fun `should handle work for server groups`() {
    Assertions.assertTrue(subject.handles(getWorkConfiguration()))
  }

  @Test
  fun `should find server groups cleanup candidates`() {
    val params = Parameters(mapOf("account" to "1234", "region" to "us-east-1"))
    whenever(serverGroupProvider.getAll(params)) doReturn listOf(
      AmazonAutoScalingGroup(
        autoScalingGroupName = "testapp-v001",
        instances = listOf(
          mapOf("instanceId" to "i-01234")
        ),
        loadBalancerNames = listOf(),
        createdTime = System.currentTimeMillis()
      ),
      AmazonAutoScalingGroup(
        autoScalingGroupName = "app-v001",
        instances = listOf(
          mapOf("instanceId" to "i-00000")
        ),
        loadBalancerNames = listOf(),
        createdTime = System.currentTimeMillis()
      )
    )

    subject.getCandidates(getWorkConfiguration()).let { serverGroups ->
      serverGroups!!.size shouldMatch equalTo(2)
    }
  }

  @Test
  fun `should find cleanup candidates, apply exclusion policies on them and mark them`() {
    val params = Parameters(mapOf("account" to "1234", "region" to "us-east-1"))
    whenever(serverGroupProvider.getAll(params)) doReturn listOf(
      AmazonAutoScalingGroup(
        autoScalingGroupName = "testapp-v001",
        instances = listOf(
          mapOf("instanceId" to "i-01234")
        ),
        loadBalancerNames = listOf(),
        createdTime = Instant.now().minus(2, ChronoUnit.DAYS).toEpochMilli()
      ),
      AmazonAutoScalingGroup(
        autoScalingGroupName = "app-v001",
        instances = listOf(),
        loadBalancerNames = listOf(),
        createdTime = Instant.now().minus(2, ChronoUnit.DAYS).toEpochMilli()
      ).apply {
        set("suspendedProcesses", listOf(
          mapOf("processName" to "AddToLoadBalancer")
        ))
      }
    )

    val workConfiguration = getWorkConfiguration(
      maxAgeDays = 1,
      exclusionList = mutableListOf(
        Exclusion()
          .withType(ExclusionType.Allowlist.toString())
          .withAttributes(
            listOf(
              Attribute()
                .withKey("autoScalingGroupName")
                .withValue(
                  listOf("app-v001")
                )
            )
          )
      )
    )

    subject.getCandidates(getWorkConfiguration()).let { serverGroups ->
      serverGroups!!.size shouldMatch equalTo(2)
    }

    subject.mark(workConfiguration, postMark= { print("Done") })

    // testapp-v001 is excluded by exclusion policies, specifically because testapp-v001 is not allowlisted
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<MarkResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "app-v001")
        Assertions.assertEquals(event.markedResource.projectedDeletionStamp.inDays(), 2)
      }
    )

    verify(resourceRepository, times(1)).upsert(any<MarkedResource>(), any<Long>())

  }

  @Test
  fun `should delete server groups`() {
    val fifteenDaysAgo = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L
    val workConfiguration = getWorkConfiguration(maxAgeDays = 1)
    val serverGroup = AmazonAutoScalingGroup(
      autoScalingGroupName = "app-v001",
      instances = listOf(),
      loadBalancerNames = listOf(),
      createdTime = Instant.now().minus(3, ChronoUnit.DAYS).toEpochMilli()
    ).apply {
      set("suspendedProcesses", listOf(
        mapOf("processName" to "AddToLoadBalancer")
      ))
    }

    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn
      listOf(
        MarkedResource(
          resource = serverGroup,
          summaries = listOf(Summary("disabled server group", "testRule 1")),
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

    whenever(serverGroupProvider.getOne(any())) doReturn listOf(serverGroup)
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

    verify(orcaService, times(1)).orchestrate(any())

    verify(resourceRepository, times(1)).remove(any<MarkedResource>())
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<DeleteResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "app-v001")
      }
    )
  }

  private fun Long.inDays(): Int
    = Duration.between(Instant.ofEpochMilli(clock.millis()), Instant.ofEpochMilli(this)).toDays().toInt()


  internal fun getWorkConfiguration(
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
              name = "serverGroup"
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
