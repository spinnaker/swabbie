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

package com.netflix.spinnaker.swabbie.aws.iamroles

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.AbstractResourceTypeHandler
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.IAM_ROLE
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.rules.RulesEngine
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import kotlin.system.measureTimeMillis

@Component
class IamRoleHandler(
  registry: Registry,
  clock: Clock,
  notifier: Notifier,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonIamRole>,
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
) : AbstractResourceTypeHandler<AmazonIamRole>(
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
  override fun deleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean {
    return workConfiguration.resourceType == IAM_ROLE && workConfiguration.cloudProvider == AWS &&
      rulesEngine.getRules(workConfiguration).isNotEmpty()
  }

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonIamRole>? {
    val params = Parameters(
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getIamRoles(params)
  }

  override fun getCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): AmazonIamRole? {
    val params = Parameters(
      id = resourceId,
      account = workConfiguration.account.accountId!!,
      region = workConfiguration.location,
      environment = workConfiguration.account.environment
    )

    return aws.getIamRole(params)
  }

  private fun checkReferences(iamRoles: List<AmazonIamRole>?, params: Parameters) {
    if (iamRoles.isNullOrEmpty()) {
      return
    }

    log.debug("checking references for {} iam roles. Parameters: {}", iamRoles.size, params)
    val elapsedTimeMillis = measureTimeMillis {
      // No references to check, the rules engine can take of the rest
    }

    log.info("Completed checking references for {} iam roles in $elapsedTimeMillis ms. Params: {}",
      iamRoles.size, params)
  }

  override fun preProcessCandidates(candidates: List<AmazonIamRole>, workConfiguration: WorkConfiguration): List<AmazonIamRole> {
    return candidates
      .also {
        checkReferences(
          iamRoles = it,
          params = Parameters(
            account = workConfiguration.account.accountId!!,
            region = workConfiguration.location,
            environment = workConfiguration.account.environment
          )
        )
      }
  }
}
