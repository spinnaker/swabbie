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

package com.netflix.spinnaker.swabbie.aws.loadbalancers

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
import com.netflix.spinnaker.swabbie.model.LOAD_BALANCER
import com.netflix.spinnaker.swabbie.model.MarkedResource
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
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class AmazonLoadBalancerHandler(
  registry: Registry,
  clock: Clock,
  notifier: Notifier,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonElasticLoadBalancer>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  swabbieProperties: SwabbieProperties,
  dynamicConfigService: DynamicConfigService,
  private val rulesEngine: RulesEngine,
  private val aws: AWS,
  private val orcaService: OrcaService,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
  private val applicationUtils: ApplicationUtils,
  notificationQueue: NotificationQueue
) : AbstractResourceTypeHandler<AmazonElasticLoadBalancer>(
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
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ) {
    markedResources.forEach { markedResource ->
      markedResource.resource.let { resource ->
        if (resource is AmazonElasticLoadBalancer && !workConfiguration.dryRun) {
          // TODO: consider also removing dns records for the ELB
          orcaService.orchestrate(
            OrchestrationRequest(
              application = applicationUtils.determineApp(resource),
              job = listOf(
                OrcaJob(
                  type = "deleteLoadBalancer",
                  context = mutableMapOf(
                    "credentials" to workConfiguration.account.name,
                    "loadBalancerName" to resource.name,
                    "cloudProvider" to resource.cloudProvider,
                    "regions" to listOf(workConfiguration.location)
                  )
                )
              ),
              description = "Deleting Load Balancer: ${resource.resourceId}"
            )
          ).let { taskResponse ->
            taskTrackingRepository.add(
              taskResponse.taskId(),
              TaskCompleteEventInfo(
                action = Action.DELETE,
                markedResources = markedResources,
                workConfiguration = workConfiguration,
                submittedTimeMillis = clock.instant().toEpochMilli()
              )
            )
          }
        }
      }
    }
  }

  override fun getCandidate(
    resourceId: String,
    resourceName: String,
    workConfiguration: WorkConfiguration
  ): AmazonElasticLoadBalancer? = aws.getElasticLoadBalancer(
    Parameters(
      id = resourceName,
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )
  )

  override fun handles(workConfiguration: WorkConfiguration): Boolean {
    return workConfiguration.resourceType == LOAD_BALANCER && workConfiguration.cloudProvider == AWS &&
      rulesEngine.getRules(workConfiguration).isNotEmpty()
  }

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonElasticLoadBalancer>? =
    aws.getElasticLoadBalancers(
      Parameters(
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
      )
    )

  override fun preProcessCandidates(
    candidates: List<AmazonElasticLoadBalancer>,
    workConfiguration: WorkConfiguration
  ): List<AmazonElasticLoadBalancer> {
    // TODO: need to check other references.
    return referenceServerGroups(
      workConfiguration = workConfiguration,
      loadBalancers = candidates
    )
  }

  private fun referenceServerGroups(
    workConfiguration: WorkConfiguration,
    loadBalancers: List<AmazonElasticLoadBalancer>
  ): List<AmazonElasticLoadBalancer> {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    val serverGroups = aws
      .getServerGroups(params)
      .also {
        if (it.isEmpty()) {
          throw IllegalStateException("Unable to retrieve server groups")
        }
      }

    serverGroups.forEach { serverGroup ->
      loadBalancers.forEach {
        it.set(isAttachedToServerGroups, serverGroup.loadBalancerNames?.contains(it.name) ?: false)
      }
    }

    return loadBalancers
  }

  companion object {
    const val isAttachedToServerGroups = "isAttachedToServerGroups"
  }
}
