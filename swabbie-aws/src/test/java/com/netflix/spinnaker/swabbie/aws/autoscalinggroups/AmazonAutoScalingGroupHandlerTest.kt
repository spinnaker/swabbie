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

import com.amazonaws.services.autoscaling.model.SuspendedProcess
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.handler.ResourceTypeHandlerTests
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.orca.OrcaExecutionStatus
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.TaskDetailResponse
import com.netflix.spinnaker.swabbie.orca.TaskResponse
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.argWhere
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object AmazonAutoScalingGroupHandlerTest : ResourceTypeHandlerTests<AmazonAutoScalingGroupHandler>() {
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val dynamicConfigService = mock<DynamicConfigService>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonAutoScalingGroup>>()
  private val aws = mock<AWS>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val orcaService = mock<OrcaService>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val notificationQueue = mock<NotificationQueue>()
  private val zeroInstanceDisabledServerGroupRule: ZeroInstanceDisabledServerGroupRule = ZeroInstanceDisabledServerGroupRule(clock)
    .withDisabledDurationInDays(1)

  private val workConfiguration = WorkConfigurationTestHelper
    .generateWorkConfiguration(resourceType = "serverGroup", cloudProvider = AWS)

  private val subject = AmazonAutoScalingGroupHandler(
    clock = clock,
    registry = NoopRegistry(),
    rules = rules(),
    notifier = mock(),
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    taskTrackingRepository = taskTrackingRepository,
    exclusionPolicies = exclusionPolicies,
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

  override fun initialize() {
    whenever(resourceOwnerResolver.resolve(any())) doReturn "lucious-mayweather@netflix.com"
  }

  override fun finalize() {
    reset(aws, resourceOwnerResolver, orcaService)
  }

  override fun rules(): List<Rule<AmazonAutoScalingGroup>> = listOf(zeroInstanceDisabledServerGroupRule)
  override fun subject(): AmazonAutoScalingGroupHandler = subject
  override fun workConfiguration(): WorkConfiguration = workConfiguration
  override fun eventPublisher(): ApplicationEventPublisher = applicationEventPublisher
  override fun resourceTrackingRepository(): ResourceTrackingRepository = resourceRepository
  override fun taskTrackingRepository(): TaskTrackingRepository = taskTrackingRepository
  override fun dynamicConfigService(): DynamicConfigService = dynamicConfigService
  override fun resourceStateRepository(): ResourceStateRepository = resourceStateRepository
  override fun getResourceById(resourceId: String): Any {
    return AmazonAutoScalingGroup(
      autoScalingGroupName = resourceId,
      resourceId = resourceId,
      instances = listOf(),
      suspendedProcesses = listOf(),
      loadBalancerNames = listOf(),
      createdTime = clock.instant().minus(3, ChronoUnit.DAYS).toEpochMilli()
    )
  }

  override fun ensureThereAreCleanupCandidates(resourceIds: List<String>, withViolations: Boolean) {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    val aDayAgo = clock.instant().minus(1, ChronoUnit.DAYS)
    val disabledAt = LocalDateTime.ofInstant(aDayAgo, ZoneId.systemDefault())
    val loadBalancers: List<String> = if (withViolations) listOf() else listOf("lb")
    val instances: List<Map<String, Any>> = if (withViolations) listOf() else listOf(mapOf("instanceId" to "i-000"))
    val suspendedProcesses = if (withViolations) listOf(
      SuspendedProcess()
        .withProcessName("AddToLoadBalancer")
        .withSuspensionReason("User suspended at $disabledAt")
    ) else listOf()

    val resources = resourceIds.map { resourceId ->
      (getResourceById(resourceId) as AmazonAutoScalingGroup).copy(
        suspendedProcesses = suspendedProcesses,
        loadBalancerNames = loadBalancers,
        instances = instances
      )
    }

    whenever(aws.getServerGroups(params)) doReturn resources
  }

  override fun ensureThereIsACleanupCandidate(resourceId: String) {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment,
      id = resourceId
    )

    whenever(aws.getServerGroup(params)) doReturn getResourceById(resourceId) as AmazonAutoScalingGroup
  }

  override fun ensureTasksToBeSubmitted(resourceIds: List<String>): List<String> {
    return resourceIds.map { resourceId ->
      val task = "task-$resourceId"
      whenever(orcaService.orchestrate(any())) doReturn TaskResponse(ref = "/tasks/$task")
      whenever(orcaService.getTask(task)) doReturn
        TaskDetailResponse(
          id = "id",
          application = "app",
          buildTime = "1",
          startTime = clock.millis().toString(),
          endTime = clock.millis().plus(1).toString(),
          status = OrcaExecutionStatus.SUCCEEDED,
          name = "delete server groups $resourceIds"
        )

      task
    }
  }

  override fun deleteTaskSubmitted(count: Int): Boolean {
    try {
      verify(orcaService, times(count)).orchestrate(
        argWhere { request ->
          request.job.any { it.type == "destroyServerGroup" }
        }
      )
    } catch (e: Throwable) {
      return false
    }

    return true
  }
}
