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

import com.netflix.spinnaker.swabbie.Notifier
import com.netflix.spinnaker.swabbie.ResourceRepository
import com.netflix.spinnaker.swabbie.aws.provider.AmazonSecurityGroupProvider
import com.netflix.spinnaker.swabbie.handlers.AbstractResourceHandler
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP
import com.netflix.spinnaker.swabbie.model.MarkedResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class AmazonSecurityGroupHandler
@Autowired constructor(
  rules: List<Rule>,
  resourceRepository: ResourceRepository,
  notifier: Notifier,
  private val amazonSecurityGroupProvider: AmazonSecurityGroupProvider
): AbstractResourceHandler(rules, resourceRepository, notifier) {
  override fun doDelete(markedResource: MarkedResource) {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun fetchResource(markedResource: MarkedResource): Resource {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun handles(resourceType: String, cloudProvider: String): Boolean {
    return resourceType == SECURITY_GROUP && cloudProvider == "aws"
  }

  override fun getNameSpace(): String {
    return "test:us-east-1:" //TODO: include the handler location
  }

  override fun fetchResources(markResourceDescription: MarkResourceDescription): List<Resource> {
    //TODO: -r jeyrs apply exclusion rules to filter out resources
    //TODO: filters should be influenced by a configuration
    //TODO: jeyrs impl this
    val filters = mapOf("region" to "us-east-1", "account" to "test")
    return amazonSecurityGroupProvider.getSecurityGroups(filters)
  }
}
