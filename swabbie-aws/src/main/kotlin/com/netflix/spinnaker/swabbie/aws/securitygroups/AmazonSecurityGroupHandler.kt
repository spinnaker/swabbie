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
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.echo.Notifier
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.*

//TODO: add rules for this handler
@Component
class AmazonSecurityGroupHandler(
  registry: Registry,
  clock: Clock,
  notifier: Notifier,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonSecurityGroup>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  lockingService: Optional<LockingService>,
  retrySupport: RetrySupport,
  private val rules: List<Rule<AmazonSecurityGroup>>,
  private val securityGroupProvider: ResourceProvider<AmazonSecurityGroup>,
  private val orcaService: OrcaService
) : AbstractResourceTypeHandler<AmazonSecurityGroup>(
  registry,
  clock,
  rules,
  resourceTrackingRepository,
  exclusionPolicies,
  resourceOwnerResolver,
  notifier,
  applicationEventPublisher,
  lockingService,
  retrySupport
) {
  override fun deleteMarkedResource(markedResource: MarkedResource, workConfiguration: WorkConfiguration) {
    markedResource.resource.let { resource ->
      if (resource is AmazonSecurityGroup && !workConfiguration.dryRun) {
        log.info("This resource is about to be deleted {}", markedResource)
        orcaService.orchestrate(
          OrchestrationRequest(
            application = FriggaReflectiveNamer().deriveMoniker(markedResource).app,
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
            description = "Cleaning up Security Group for ${FriggaReflectiveNamer().deriveMoniker(markedResource).app}"
          )
        )
      }
    }
  }

  override fun getCandidate(markedResource: MarkedResource,
                            workConfiguration: WorkConfiguration
  ): AmazonSecurityGroup? = securityGroupProvider.getOne(
    Parameters(
      mapOf(
        "groupId" to markedResource.resourceId,
        "account" to workConfiguration.account.accountId!!,
        "region" to workConfiguration.location
      )
    )
  )


  override fun handles(workConfiguration: WorkConfiguration): Boolean
    = workConfiguration.resourceType == SECURITY_GROUP && workConfiguration.cloudProvider == AWS && !rules.isEmpty()

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonSecurityGroup>? =
    securityGroupProvider.getAll(
      Parameters(
        mapOf(
          "account" to workConfiguration.account.accountId!!,
          "region" to workConfiguration.location
        )
      )
    )
}
