/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.aws.launchtemplates

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.AbstractResourceTypeHandler
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.LAUNCH_TEMPLATE
import com.netflix.spinnaker.swabbie.model.ResourcePartition
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
class AmazonLaunchTemplateHandler(
  registry: Registry,
  clock: Clock,
  notifier: Notifier,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonLaunchTemplate>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  swabbieProperties: SwabbieProperties,
  dynamicConfigService: DynamicConfigService,
  private val rulesEngine: RulesEngine,
  private val aws: AWS,
  private val orcaService: OrcaService,
  private val applicationUtils: ApplicationUtils,
  private val taskTrackingRepository: TaskTrackingRepository,
  resourceUseTrackingRepository: ResourceUseTrackingRepository,
  notificationQueue: NotificationQueue
) : AbstractResourceTypeHandler<AmazonLaunchTemplate>(
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
  override fun deleteResources(resourcePartition: ResourcePartition, workConfiguration: WorkConfiguration) {
    val application = applicationUtils.determineApp(resourcePartition.markedResources.first().resource)
    val launTemplateIds = resourcePartition.markedResources
      .map {
        it.resourceId
      }.toSet()

    orcaService.orchestrate(
      OrchestrationRequest(
        application = application,
        job = listOf(
          OrcaJob(
            type = "deleteLaunchTemplate",
            context = mutableMapOf(
              "credentials" to workConfiguration.account.name,
              "launchTemplateIds" to launTemplateIds,
              "cloudProvider" to AWS,
              "region" to workConfiguration.location
            )
          )
        ),
        description = "Deleting Launch Templates: $launTemplateIds"
      )
    ).let { taskResponse ->
      val completeEvent = TaskCompleteEventInfo(
        action = Action.DELETE,
        markedResources = resourcePartition.markedResources,
        workConfiguration = workConfiguration,
        submittedTimeMillis = clock.instant().toEpochMilli()
      )

      taskTrackingRepository.add(taskResponse.taskId(), completeEvent)
    }
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean {
    return workConfiguration.resourceType == LAUNCH_TEMPLATE && workConfiguration.cloudProvider == AWS &&
      rulesEngine.getRules(workConfiguration).isNotEmpty()
  }

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonLaunchTemplate>? {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getLaunchTemplates(params)
  }

  override fun getCandidate(
    resourceId: String,
    resourceName: String,
    workConfiguration: WorkConfiguration
  ): AmazonLaunchTemplate? {
    val params = Parameters(
      id = resourceId,
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getLaunchTemplate(params)
  }

  override fun preProcessCandidates(
    candidates: List<AmazonLaunchTemplate>,
    workConfiguration: WorkConfiguration
  ): List<AmazonLaunchTemplate> {
    return candidates
      .also {
        checkReferences(
          launchTemplates = it,
          params = Parameters(
            account = workConfiguration.account.accountId!!,
            region = workConfiguration.location,
            environment = workConfiguration.account.environment
          )
        )
      }
  }

  /**
   * Checks references for:
   * Server Groups
   */
  private fun checkReferences(launchTemplates: List<AmazonLaunchTemplate>, params: Parameters) {
    if (launchTemplates.isNullOrEmpty()) {
      return
    }

    log.debug("checking references for {} launch template. Parameters: {}", launchTemplates.size, params)
    val elapsedTimeMillis = measureTimeMillis {
      setServerGroupReferences(launchTemplates, params)
    }

    log.info(
      "Completed checking references for {} launch templates in $elapsedTimeMillis ms. Params: {}",
      launchTemplates.size, params
    )
  }

  private fun setServerGroupReferences(launchTemplates: List<AmazonLaunchTemplate>, params: Parameters) {
    val launchTemplatesWithServerGroups = aws
      .getServerGroups(params)
      .filter { it.launchTemplate != null }
      .map {
        it.launchTemplate!!.launchTemplateId
      }

    launchTemplates.forEach { lt ->
      lt.set(isUsedByServerGroups, lt.resourceId in launchTemplatesWithServerGroups)
    }
  }

  companion object {
    const val isUsedByServerGroups = "isUsedByServerGroups"
  }
}
