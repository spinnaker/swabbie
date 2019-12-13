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

import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * An Elastic Load Balancer is invalid if it's not referenced by any server groups and has no instances
 */
@Component
class OrphanedELBRule : Rule {
  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    if (resource !is AmazonElasticLoadBalancer || resource.details["instances"] != null && (resource.details["instances"] as List<*>).size > 0) {
      return Result(null)
    }

    if (resource.details["serverGroups"] != null && (resource.details["serverGroups"] as List<*>).size > 0) {
      return Result(null)
    }

    log.debug("Load Balancer {} has no instances and is not referenced by any Server Group", resource)
    return Result(
      Summary(
        description = "Load Balancer has no instances and is not referenced by any Server Group",
        ruleName = javaClass.simpleName
      )
    )
  }

  private val log: Logger = LoggerFactory.getLogger(javaClass)
}
