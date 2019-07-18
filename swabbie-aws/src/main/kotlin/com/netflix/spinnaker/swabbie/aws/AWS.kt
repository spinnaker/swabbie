/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.swabbie.aws

import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.aws.loadbalancers.AmazonElasticLoadBalancer
import com.netflix.spinnaker.swabbie.aws.securitygroups.AmazonSecurityGroup
import com.netflix.spinnaker.swabbie.aws.snapshots.AmazonSnapshot

interface AWS {
  fun getInstances(params: Parameters): List<AmazonInstance>
  fun getInstance(params: Parameters): AmazonInstance?
  fun getLaunchConfigurations(params: Parameters): List<AmazonLaunchConfiguration>
  fun getLaunchConfiguration(params: Parameters): AmazonLaunchConfiguration?
  fun getElasticLoadBalancers(params: Parameters): List<AmazonElasticLoadBalancer>
  fun getElasticLoadBalancer(params: Parameters): AmazonElasticLoadBalancer?
  fun getImages(params: Parameters): List<AmazonImage>
  fun getImage(params: Parameters): AmazonImage?
  fun getSecurityGroups(params: Parameters): List<AmazonSecurityGroup>
  fun getSecurityGroup(params: Parameters): AmazonSecurityGroup?
  fun getSnapshots(params: Parameters): List<AmazonSnapshot>
  fun getSnapshot(params: Parameters): AmazonSnapshot?
  fun getServerGroups(params: Parameters): List<AmazonAutoScalingGroup>
  fun getServerGroup(params: Parameters): AmazonAutoScalingGroup?
}
