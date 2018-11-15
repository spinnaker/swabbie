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

package com.netflix.spinnaker.swabbie.aws.securitygroups

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import com.netflix.spinnaker.swabbie.repository.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.*

//TODO: add rules for this handler
@Component
class AmazonSecurityGroupHandler(
  registry: Registry,
  clock: Clock,
  notifiers: List<Notifier>,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonSecurityGroup>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  lockingService: Optional<LockingService>,
  retrySupport: RetrySupport,
  swabbieProperties: SwabbieProperties,
  private val rules: List<Rule<AmazonSecurityGroup>>,
  private val securityGroupProvider: ResourceProvider<AmazonSecurityGroup>,
  private val orcaService: OrcaService,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository
) : AbstractResourceTypeHandler<AmazonSecurityGroup>(
  registry,
  clock,
  rules,
  resourceTrackingRepository,
  resourceStateRepository,
  exclusionPolicies,
  resourceOwnerResolver,
  notifiers,
  applicationEventPublisher,
  lockingService,
  retrySupport,
  resourceUseTrackingRepository,
  swabbieProperties
) {
  override fun deleteResources(
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ) {
    markedResources.forEach { markedResource ->
      markedResource.resource.let { resource ->
        if (resource is AmazonSecurityGroup && !workConfiguration.dryRun) {
          log.info("This resource is about to be deleted {}", markedResource)
          orcaService.orchestrate(
            OrchestrationRequest(
              application = determineApp(resource),
              job = listOf(
                OrcaJob(
                  type = "deleteSecurityGroup",
                  context = mutableMapOf(
                    "credentials" to workConfiguration.account.name,
                    "securityGroupName" to resource.groupName,
                    "cloudProvider" to resource.cloudProvider,
                    "vpcId" to resource.vpcId,
                    "regions" to listOf(workConfiguration.location)
                  )
                )
              ),
              description = "Cleaning up Security Group for ${resource.grouping?.value.orEmpty()}"
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

  private fun determineApp(resource: Resource): String {
    val grouping: Grouping = resource.grouping?: return "swabbie"
    if (grouping.type == GroupingType.APPLICATION) {
      return grouping.value
    }
    return "swabbie"
  }

  override fun softDeleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    TODO("not implemented")
  }

  override fun restoreResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    TODO("not implemented")
  }

  override fun getCandidate(resourceId: String,
                            resourceName: String,
                            workConfiguration: WorkConfiguration
  ): AmazonSecurityGroup? = securityGroupProvider.getOne(
    Parameters(
        id = resourceId,
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
    )
  )

  override fun preProcessCandidates(
    candidates: List<AmazonSecurityGroup>,
    workConfiguration: WorkConfiguration
  ): List<AmazonSecurityGroup> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun handles(workConfiguration: WorkConfiguration):
    Boolean = workConfiguration.resourceType == SECURITY_GROUP &&
    workConfiguration.cloudProvider == AWS &&
    !rules.isEmpty()

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonSecurityGroup>? =
    securityGroupProvider.getAll(
      Parameters(
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
      )
    )
}
