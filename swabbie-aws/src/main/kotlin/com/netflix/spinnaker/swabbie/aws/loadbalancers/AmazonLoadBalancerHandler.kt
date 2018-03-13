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

import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class AmazonLoadBalancerHandler(
  clock: Clock,
  private val rules: List<Rule<AmazonElasticLoadBalancer>>,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceOwnerResolver: ResourceOwnerResolver,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  private val loadBalancerProvider: ResourceProvider<AmazonElasticLoadBalancer>,
  private val serverGroupProvider: ResourceProvider<AmazonAutoScalingGroup>,
  private val orcaService: OrcaService
): AbstractResourceHandler<AmazonElasticLoadBalancer>(clock, rules, resourceTrackingRepository, exclusionPolicies, resourceOwnerResolver, applicationEventPublisher) {
  override fun remove(markedResource: MarkedResource, workConfiguration: WorkConfiguration) {
    markedResource.resource.let { resource ->
      if (resource is AmazonElasticLoadBalancer) {
        //TODO: consider also removing dns records for the ELB
        //TODO: review delete action
        log.info("This load balancer is about to be deleted {}", markedResource)
        orcaService.orchestrate(
          OrchestrationRequest(
            application = FriggaReflectiveNamer().deriveMoniker(markedResource).app,
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
            description = "Cleaning up Load Balancer for ${FriggaReflectiveNamer().deriveMoniker(markedResource).app}"
          )
        )
      }
    }
  }

  override fun getUpstreamResource(markedResource: MarkedResource, workConfiguration: WorkConfiguration): AmazonElasticLoadBalancer? =
    loadBalancerProvider.getOne(
      Parameters(
        mapOf(
          "loadBalancerName" to markedResource.name,
          "account" to workConfiguration.account.name,
          "region" to workConfiguration.location
        )
      )
    )

  override fun handles(resourceType: String, cloudProvider: String): Boolean
    = resourceType == LOAD_BALANCER && cloudProvider == AWS && !rules.isEmpty()

  override fun getUpstreamResources(workConfiguration: WorkConfiguration): List<AmazonElasticLoadBalancer>? =
    loadBalancerProvider.getAll(
      Parameters(
        mapOf(
          "account" to workConfiguration.account.name,
          "region" to workConfiguration.location
        )
      )
    ).let { loadBalancers ->
      if (loadBalancers != null && !loadBalancers.isEmpty()) {
        referenceServerGroups(workConfiguration, loadBalancers)
      } else {
        emptyList()
      }
    }

  private fun referenceServerGroups(workConfiguration: WorkConfiguration,
                                    loadBalancers: List<AmazonElasticLoadBalancer>): List<AmazonElasticLoadBalancer> {
    serverGroupProvider.getAll(
      Parameters(
        mapOf(
          "account" to workConfiguration.account.name,
          "region" to workConfiguration.location
        )
      )
    ).let { serverGroups ->
      if (serverGroups == null || serverGroups.isEmpty()) {
        throw IllegalStateException("Unable to retrieve server groups")
      }

      serverGroups.forEach { serverGroup ->
        loadBalancers.addServerGroupReferences(serverGroup)
      }
    }

    return loadBalancers
  }

  private fun List<AmazonElasticLoadBalancer>.addServerGroupReferences(serverGroup: AmazonAutoScalingGroup) =
    this.filter {
      (serverGroup.details["loadBalancerNames"] as List<*>).contains(it.name)
    }.map { elb ->
        elb.details["serverGroups"] = elb.details["serverGroups"] ?: mutableListOf<String>()
        (elb.details["serverGroups"] as MutableList<String>).add(serverGroup.name)
      }
}

