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
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import com.netflix.spinnaker.swabbie.work.WorkConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock

//TODO: add rules for this handler
@Component
class AmazonSecurityGroupHandler(
  registry: Registry,
  clock: Clock,
  rules: List<Rule<AmazonSecurityGroup>>,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceOwnerResolver: ResourceOwnerResolver,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  private val securityGroupProvider: ResourceProvider<AmazonSecurityGroup>,
  private val orcaService: OrcaService
) : AbstractResourceHandler<AmazonSecurityGroup>(registry, clock, rules, resourceTrackingRepository, exclusionPolicies, resourceOwnerResolver, applicationEventPublisher) {
  override fun remove(markedResource: MarkedResource, workConfiguration: WorkConfiguration) {
    markedResource.resource.let { resource ->
      if (resource is AmazonSecurityGroup) {
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

  override fun getUpstreamResource(markedResource: MarkedResource, workConfiguration: WorkConfiguration): AmazonSecurityGroup? =
    securityGroupProvider.getOne(
      Parameters(
        mapOf(
          "groupId" to markedResource.resourceId,
          "account" to workConfiguration.account.name,
          "region" to workConfiguration.location
        )
      )
    )

  //TODO: enable when rules are added
  override fun handles(workConfiguration: WorkConfiguration): Boolean = false
//    = workConfiguration.resourceType == SECURITY_GROUP && workConfiguration.cloudProvider == AWS && !rules.isEmpty()

  override fun getUpstreamResources(workConfiguration: WorkConfiguration): List<AmazonSecurityGroup>? =
    securityGroupProvider.getAll(
      Parameters(
        mapOf(
          "account" to workConfiguration.account.name,
          "region" to workConfiguration.location
        )
      )
    )
}
