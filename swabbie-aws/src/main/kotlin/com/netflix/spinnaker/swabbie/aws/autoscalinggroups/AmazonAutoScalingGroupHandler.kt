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
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.*
import kotlin.system.measureTimeMillis

@Component
class AmazonAutoScalingGroupHandler(
  registry: Registry,
  clock: Clock,
  notifiers: List<Notifier>,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonAutoScalingGroup>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  lockingService: Optional<LockingService>,
  retrySupport: RetrySupport,
  private val rules: List<Rule<AmazonAutoScalingGroup>>,
  private val serverGroupProvider: ResourceProvider<AmazonAutoScalingGroup>,
  private val orcaService: OrcaService
) : AbstractResourceTypeHandler<AmazonAutoScalingGroup>(
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
  retrySupport
) {
  override fun deleteResources(
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ): ReceiveChannel<MarkedResource> = produce<MarkedResource> {
    markedResources.forEach { markedResource ->
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
        // "ref": "/tasks/01CK1Y63QFEP4ETC6P5DARECV6"
        val taskId = taskResponse.ref.substring(taskResponse.ref.lastIndexOf("/") + 1)
        var taskStatus = orcaService.getTask(taskId).status
        runBlocking {
          while (taskStatus.isIncomplete()) {
            delay(checkStatusDelay)
            taskStatus = orcaService.getTask(taskId).status
          }
        }

        if (!taskStatus.isSuccess()) {
          log.error("Failed to delete server group ${markedResource.resourceId}")
        } else {
          send(markedResource)
        }
      }
    }
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean
    = workConfiguration.resourceType == SERVER_GROUP && workConfiguration.cloudProvider == AWS && !rules.isEmpty()

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonAutoScalingGroup>? {
    val params = Parameters(
      mapOf("account" to workConfiguration.account.accountId!!, "region" to workConfiguration.location)
    )

    return serverGroupProvider.getAll(params).also { serverGroups ->
      log.info("Got {} Server Groups. Checking references", serverGroups?.size)
      checkReferences(serverGroups, params)
    }
  }

  private fun checkReferences(serverGroups: List<AmazonAutoScalingGroup>?, params: Parameters) {
    if (serverGroups == null || serverGroups.isEmpty()) {
      return
    }
    val elapsedTimeMillis = measureTimeMillis {
      serverGroups.forEach { serverGroup ->
        if (HAS_INSTANCES !in serverGroup.details) {
          serverGroup.set(HAS_INSTANCES, serverGroup.instances != null && !serverGroup.instances.isEmpty())
        }

        (serverGroup.details["suspendedProcesses"] as? List<Map<String, Any>>)?.find {
          it["processName"] == "AddToLoadBalancer"
        }?.let {
          serverGroup.set(IS_DISABLED, true)
        }
      }
    }

    log.info("Completed checking references for {} server groups in $elapsedTimeMillis ms. Params: {}",
      serverGroups.size, params)
  }

  override fun getCandidate(
    markedResource: MarkedResource,
    workConfiguration: WorkConfiguration
  ): AmazonAutoScalingGroup? {
    val params = Parameters(mapOf(
      "autoScalingGroupName" to markedResource.resourceId,
      "account" to workConfiguration.account.accountId!!,
      "region" to workConfiguration.location)
    )

    return serverGroupProvider.getOne(params)?.also { serverGroup ->
      checkReferences(listOf(serverGroup), params)
    }
  }
}

const val checkStatusDelay = 6000
