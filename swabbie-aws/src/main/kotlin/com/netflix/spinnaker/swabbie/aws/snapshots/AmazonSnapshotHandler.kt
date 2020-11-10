/*
 *
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.aws.snapshots

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
import com.netflix.spinnaker.swabbie.model.ResourcePartition
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import com.netflix.spinnaker.swabbie.orca.generatedWaitStageWithFixedWaitTime
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import com.netflix.spinnaker.swabbie.rules.RulesEngine
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class AmazonSnapshotHandler(
  registry: Registry,
  clock: Clock,
  notifier: Notifier,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonSnapshot>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  dynamicConfigService: DynamicConfigService,
  private val rulesEngine: RulesEngine,
  private val aws: AWS,
  private val orcaService: OrcaService,
  private val applicationUtils: ApplicationUtils,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
  private val usedResourceRepository: UsedResourceRepository,
  private val swabbieProperties: SwabbieProperties,
  notificationQueue: NotificationQueue
) : AbstractResourceTypeHandler<AmazonSnapshot>(
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

  @Value("\${swabbie.clean.jitter-interval:600}")
  private var cleanInterval: Long = 600

  /**
   * Deletes resources in a two part request to stagger the deletion.
   * First, we wait for a random amount of time.
   * Then, we do the delete.
   */
  override fun deleteResources(resourcePartition: ResourcePartition, workConfiguration: WorkConfiguration) {
    orcaService.orchestrate(
      OrchestrationRequest(
        // resources are partitioned based on grouping, so find the app to use from first resource
        application = applicationUtils.determineApp(resourcePartition.markedResources.first().resource),
        job = listOf(
          generatedWaitStageWithFixedWaitTime(resourcePartition.offsetMs),
          OrcaJob(
            type = "deleteSnapshot",
            context = mutableMapOf(
              "credentials" to workConfiguration.account.name,
              "snapshotIds" to resourcePartition.markedResources.map { it.resourceId }.toSet(),
              "cloudProvider" to AWS,
              "region" to workConfiguration.location,
              "requisiteStageRefIds" to listOf("0")
            )
          )
        ),
        description = "Deleting Snapshots: ${resourcePartition.markedResources.map { it.resourceId }}"
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

  override fun handles(workConfiguration: WorkConfiguration): Boolean =
    workConfiguration.resourceType == SNAPSHOT && workConfiguration.cloudProvider == AWS &&
      rulesEngine.getRules(workConfiguration).isNotEmpty()

  override fun getCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): AmazonSnapshot? {
    val params = Parameters(
      id = resourceId,
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getSnapshot(params)
  }

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonSnapshot>? {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getSnapshots(params).also { snapshots ->
      log.info("Got {} snapshots.", snapshots.size)
    }
  }

  override fun preProcessCandidates(
    candidates: List<AmazonSnapshot>,
    workConfiguration: WorkConfiguration
  ): List<AmazonSnapshot> {
    checkIfImagesExist(
      candidates = candidates,
      params = Parameters(
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
      )
    )
    return candidates
  }

  private fun checkIfImagesExist(candidates: List<AmazonSnapshot>, params: Parameters) {
    log.info("Checking for existing images for {} snapshots. Params: {} ", candidates.size, params)
    candidates.forEach { snapshot ->
      if (usedResourceRepository.isUsed(SNAPSHOT, snapshot.snapshotId, "aws:${params.region}:${params.account}")) {
        snapshot.set(IMAGE_EXISTS, true)
      }
    }
  }
}
