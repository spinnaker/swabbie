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

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NotificationInfo
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.orca.OrcaExecutionStatus
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.TaskDetailResponse
import com.netflix.spinnaker.swabbie.orca.TaskResponse
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.rules.ResourceRulesEngine
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verifyNoMoreInteractions
import com.nhaarman.mockito_kotlin.validateMockitoUsage
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

object AmazonAutoScalingGroupHandlerTest {
  private val aws = mock<AWS>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonAutoScalingGroup>>()
  private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val orcaService = mock<OrcaService>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val dynamicConfigService = mock<DynamicConfigService>()
  private val notificationQueue = mock<NotificationQueue>()
  private val rulesEngine = mock<ResourceRulesEngine>()
  private val ruleAndViolationPair = Pair<Rule, List<Summary>>(mock(), listOf(Summary("violate rule", ruleName = "rule")))
  private val workConfiguration = WorkConfigurationTestHelper
    .generateWorkConfiguration(resourceType = SERVER_GROUP, cloudProvider = AWS)

  private val params = Parameters(
    account = workConfiguration.account.accountId!!,
    region = workConfiguration.location,
    environment = workConfiguration.account.environment
  )

  private val subject = AmazonAutoScalingGroupHandler(
    clock = clock,
    registry = NoopRegistry(),
    rulesEngine = rulesEngine,
    notifier = mock(),
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    taskTrackingRepository = taskTrackingRepository,
    exclusionPolicies = listOf(),
    resourceOwnerResolver = resourceOwnerResolver,
    applicationEventPublisher = applicationEventPublisher,
    aws = aws,
    orcaService = orcaService,
    resourceUseTrackingRepository = resourceUseTrackingRepository,
    swabbieProperties = SwabbieProperties().also { it.schedule.enabled = false },
    dynamicConfigService = dynamicConfigService,
    notificationQueue = notificationQueue,
    applicationUtils = ApplicationUtils(emptyList())
  )

  private const val user = "test@netflix.com"
  private val sg1 = AmazonAutoScalingGroup(
    autoScalingGroupName = "testapp-v001",
    instances = listOf(),
    loadBalancerNames = listOf(),
    createdTime = clock.millis()
  )

  private val sg2 = AmazonAutoScalingGroup(
    autoScalingGroupName = "app-v001",
    instances = listOf(),
    loadBalancerNames = listOf(),
    createdTime = clock.millis()
  )

  @BeforeEach
  fun setup() {
    whenever(resourceOwnerResolver.resolve(any())) doReturn user
    whenever(aws.getServerGroups(params)) doReturn listOf(sg1, sg2)
    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle
  }

  @AfterEach
  fun cleanup() {
    validateMockitoUsage()
    reset(aws, resourceRepository, applicationEventPublisher, resourceOwnerResolver, rulesEngine, taskTrackingRepository)
  }

  @Test
  fun `should handle server groups`() {
    whenever(rulesEngine.getRules(workConfiguration)) doReturn listOf(ruleAndViolationPair.first)
    expectThat(subject.handles(workConfiguration)).isTrue()

    whenever(rulesEngine.getRules(workConfiguration)) doReturn emptyList<Rule>()
    expectThat(subject.handles(workConfiguration)).isFalse()
  }

  @Test
  fun `should get server groups`() {
    expectThat(subject.getCandidates(workConfiguration)!!.count()).isEqualTo(2)
  }

  @Test
  fun `should mark server groups`() {
    whenever(rulesEngine.evaluate(any<AmazonAutoScalingGroup>(), any())) doReturn ruleAndViolationPair.second

    subject.mark(workConfiguration)

    verify(resourceRepository, times(2)).upsert(any(), any())
    verify(applicationEventPublisher, times(2)).publishEvent(any<MarkResourceEvent>())
    verifyNoMoreInteractions(applicationEventPublisher)
  }

  @Test
  fun `should delete server groups`() {
    val markedResources = listOf(
      MarkedResource(
        resource = sg1,
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

    whenever(rulesEngine.evaluate(any<AmazonAutoScalingGroup>(), any())) doReturn ruleAndViolationPair.second
    whenever(resourceRepository.getMarkedResourcesToDelete()) doReturn markedResources

    whenever(aws.getServerGroups(params)) doReturn listOf(sg1, sg2)
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

    subject.delete(workConfiguration)

    verify(orcaService).orchestrate(any())
    verify(taskTrackingRepository).add(any(), any())
    verify(applicationEventPublisher).publishEvent(any<DeleteResourceEvent>())

    verifyNoMoreInteractions(applicationEventPublisher)
    verifyNoMoreInteractions(orcaService)
  }
}
