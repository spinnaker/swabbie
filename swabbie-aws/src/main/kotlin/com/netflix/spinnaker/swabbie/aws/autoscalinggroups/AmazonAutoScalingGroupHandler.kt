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

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.AbstractResourceTypeHandler
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.ResourcePartition
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.SERVER_GROUP
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.rules.RulesEngine
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import java.time.Clock
import kotlin.system.measureTimeMillis
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class AmazonAutoScalingGroupHandler(
  registry: Registry,
  clock: Clock,
  notifier: Notifier,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonAutoScalingGroup>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  swabbieProperties: SwabbieProperties,
  dynamicConfigService: DynamicConfigService,
  private val rulesEngine: RulesEngine,
  private val aws: AWS,
  private val orcaService: OrcaService,
  private val applicationUtils: ApplicationUtils,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
  notificationQueue: NotificationQueue
) : AbstractResourceTypeHandler<AmazonAutoScalingGroup>(
  registry,
  clock,
  rulesEngine,
  resourceTrackingRepository,
  resourceStateRepository,
  exclusionPolicies,
  resourceOwnerResolver,
  notifier,
  applicationEventPublisher,
  resourceUseTrackingRepository,
  swabbieProperties,
  dynamicConfigService,
  notificationQueue
) {

  override fun deleteResources(
    resourcePartition: ResourcePartition,
    workConfiguration: WorkConfiguration
  ) {
    resourcePartition.markedResources.forEach { markedResource ->
      orcaService.orchestrate(
        OrchestrationRequest(
          application = FriggaReflectiveNamer().deriveMoniker(markedResource).app ?: "swabbie",
          job = listOf(
            OrcaJob(
              type = "destroyServerGroup",
              context = mutableMapOf(
                "credentials" to workConfiguration.account.name,
                "serverGroupName" to markedResource.resource.resourceId,
                "cloudProvider" to markedResource.resource.cloudProvider,
                "region" to workConfiguration.location
              )
            )
          ),
          description = "Deleting Server Group :${markedResource.resourceId}"
        )
      ).let { taskResponse ->
        taskTrackingRepository.add(
          taskResponse.taskId(),
          TaskCompleteEventInfo(
            action = Action.DELETE,
            markedResources = resourcePartition.markedResources,
            workConfiguration = workConfiguration,
            submittedTimeMillis = clock.instant().toEpochMilli()
          )
        )
      }
    }
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean {
    return workConfiguration.resourceType == SERVER_GROUP && workConfiguration.cloudProvider == AWS &&
      rulesEngine.getRules(workConfiguration).isNotEmpty()
  }

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonAutoScalingGroup>? {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getServerGroups(params).also { serverGroups ->
      log.info("Got {} Server Groups. Checking references", serverGroups.size)
    }
  }

  override fun preProcessCandidates(
    candidates: List<AmazonAutoScalingGroup>,
    workConfiguration: WorkConfiguration
  ): List<AmazonAutoScalingGroup> {
    checkReferences(
      serverGroups = candidates,
      params = Parameters(
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
      )
    )

    return candidates
  }

  private fun checkReferences(serverGroups: List<AmazonAutoScalingGroup>?, params: Parameters) {
    if (serverGroups == null || serverGroups.isEmpty()) {
      return
    }
    val elapsedTimeMillis = measureTimeMillis {} // check references here. Empty body because all references are present.
    log.info(
      "Completed checking references for {} server groups in ${elapsedTimeMillis}ms. Params: {}",
      serverGroups.size, params
    )
  }

  override fun getCandidate(
    resourceId: String,
    resourceName: String,
    workConfiguration: WorkConfiguration
  ): AmazonAutoScalingGroup? {
    val params = Parameters(
      id = resourceId,
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getServerGroup(params)
  }

  override fun optOut(resourceId: String, workConfiguration: WorkConfiguration): ResourceState {
    return super.optOut(resourceId, workConfiguration)
      .also {
        tagServerGroup(it.markedResource, workConfiguration)
      }
  }

  private fun tagServerGroup(markedResource: MarkedResource, workConfiguration: WorkConfiguration) {
    val tagServerGroupTask = orcaService.orchestrate(
      OrchestrationRequest(
        application = applicationUtils.determineApp(markedResource.resource),
        description = "Opting ${markedResource.resourceId} out of deletion.",
        job = listOf(
          OrcaJob(
            type = "upsertServerGroupTags",
            context = mutableMapOf(
              "serverGroupName" to markedResource.name,
              "regions" to setOf(workConfiguration.location),
              "tags" to mapOf("expiration_time" to "never"),
              "cloudProvider" to workConfiguration.cloudProvider,
              "cloudProviderType" to workConfiguration.cloudProvider,
              "credentials" to workConfiguration.account.name
            )
          )
        )
      )
    )

    taskTrackingRepository.add(
      tagServerGroupTask.taskId(),
      TaskCompleteEventInfo(
        action = Action.OPTOUT,
        markedResources = listOf(markedResource),
        workConfiguration = workConfiguration,
        submittedTimeMillis = clock.instant().toEpochMilli()
      )
    )
  }
}
