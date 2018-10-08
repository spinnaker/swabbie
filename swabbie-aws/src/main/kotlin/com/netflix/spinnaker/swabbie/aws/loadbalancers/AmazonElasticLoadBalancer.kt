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

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.ResourceType

@JsonTypeName("amazonElasticLoadBalancer")
data class AmazonElasticLoadBalancer(
  private val loadBalancerName: String?, //TODO: stupid hack, fix this
  override val resourceType: String = ResourceType.LOAD_BALANCER.toString(),
  override val name: String = loadBalancerName!!,
  override val resourceId: String = loadBalancerName!!,
  override val cloudProvider: String = AWS,
  private val creationDate: String?
) : AmazonResource(creationDate)
