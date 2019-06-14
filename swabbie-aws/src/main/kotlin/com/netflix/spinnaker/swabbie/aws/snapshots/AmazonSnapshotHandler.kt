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
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.AbstractResourceTypeHandler
import com.netflix.spinnaker.swabbie.LockingService
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.ResourceProvider
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NAIVE_EXCLUSION
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import com.netflix.spinnaker.swabbie.orca.generateWaitStageWithRandWaitTime
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Optional

@Component
class AmazonSnapshotHandler(
  registry: Registry,
  clock: Clock,
  notifiers: List<Notifier>,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonSnapshot>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  lockingService: Optional<LockingService>,
  retrySupport: RetrySupport,
  dynamicConfigService: DynamicConfigService,
  private val rules: List<Rule<AmazonSnapshot>>,
  private val snapshotProvider: ResourceProvider<AmazonSnapshot>,
  private val orcaService: OrcaService,
  private val applicationUtils: ApplicationUtils,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
  private val usedResourceRepository: UsedResourceRepository,
  private val swabbieProperties: SwabbieProperties
) : AbstractResourceTypeHandler<AmazonSnapshot>(
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
  swabbieProperties,
  dynamicConfigService
) {

  @Value("\${swabbie.agents.clean.interval-seconds:3600}")
  private var cleanInterval: Long = 3600

  /**
   * Deletes resources in a two part request to stagger the deletion.
   * First, we wait for a random amount of time.
   * Then, we do the delete.
   */
  override fun deleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    orcaService.orchestrate(
      OrchestrationRequest(
        // resources are partitioned based on grouping, so find the app to use from first resource
        application = applicationUtils.determineApp(markedResources.first().resource),
        job = listOf(
          generateWaitStageWithRandWaitTime(cleanInterval),
          OrcaJob(
            type = "deleteSnapshot",
            context = mutableMapOf(
              "credentials" to workConfiguration.account.name,
              "snapshotIds" to markedResources.map { it.resourceId }.toSet(),
              "cloudProvider" to AWS,
              "region" to workConfiguration.location,
              "requisiteStageRefIds" to listOf("0")
            )
          )
        ),
        description = "Deleting Snapshots: ${markedResources.map { it.resourceId }}"
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
      log.debug("Deleting resources ${markedResources.map { it.uniqueId() }} in orca task ${taskResponse.taskId()}")
    }
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean =
    workConfiguration.resourceType == SNAPSHOT && workConfiguration.cloudProvider == AWS && !rules.isEmpty()

  override fun getCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): AmazonSnapshot? {
    val params = Parameters(
      id = resourceId,
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return snapshotProvider.getOne(params)
  }

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonSnapshot>? {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return snapshotProvider.getAll(params).also { snapshots ->
      log.info("Got {} snapshots.", snapshots?.size)
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
      ))
    return candidates
  }

  private fun checkIfImagesExist(candidates: List<AmazonSnapshot>, params: Parameters) {
    log.info("Checking for existing images for {} snapshots. Params: {} ", candidates.size, params)
    candidates.forEach { snapshot ->
      if (snapshot.description == null) {
        snapshot.set(NAIVE_EXCLUSION, true)
        return
      }

      if (descriptionIsFromAutoBake(snapshot.description)) {
          // if description contains these keys then it's most likely an auto-captured snapshot.
        if (usedResourceRepository.isUsed(SNAPSHOT, snapshot.snapshotId, "aws:${params.region}:${params.account}")) {
          snapshot.set(IMAGE_EXISTS, true)
        }
      } else {
        snapshot.set(NAIVE_EXCLUSION, true)
      }
    }
  }

  // todo eb: generalize this netflix-specific pattern
  fun descriptionIsFromAutoBake(description: String): Boolean {
    // description=name=nflx-sirt-forensics, arch=x86_64, ancestor_name=bionic-classicbase-unstable-x86_64-201902272237-ebs, ancestor_id=ami-067321616378baefc, ancestor_version=nflx-base-5.347.2-h1144.4aace97~unstable
    val pairs = description.replace(" ", "").split(",")
    val parsedDescr = mutableMapOf<String, String>()
    pairs.forEach { pair ->
      val split = pair.split("=")
      if (split.size == 2) {
        parsedDescr[split[0]] = split[1]
      }
    }

    return parsedDescr.containsKey("name") && parsedDescr.containsKey("ancestor_name") &&
      parsedDescr.containsKey("ancestor_id") && parsedDescr.containsKey("ancestor_version")
  }
}
