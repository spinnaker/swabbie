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

package com.netflix.spinnaker.swabbie.aws.handlers

import com.netflix.spinnaker.swabbie.aws.provider.AmazonSecurityGroupProvider
import com.netflix.spinnaker.swabbie.handlers.AbstractResourceHandler
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Rule
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AmazonSecurityGroupHandler(
  private val rules: List<Rule>,
  private val amazonSecurityGroupProvider: AmazonSecurityGroupProvider
): AbstractResourceHandler(rules) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun getResources(): List<Resource> {
    //TODO: how to exclude based on exclusion rules
    //TODO: filters should be influenced by a configuration
    val filters = mapOf("region" to "us-east-1", "account" to "test")
    return amazonSecurityGroupProvider.getSecurityGroups(filters)
  }

  override fun filter(resources: List<Resource>): List<Resource> {
    //TODO: filtering allows to exclude resources that are meant to be skipped by swabbie
    return resources
  }
}
