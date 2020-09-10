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

package com.netflix.spinnaker.swabbie.aws.edda

import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.aws.launchtemplates.AmazonLaunchTemplate
import com.netflix.spinnaker.swabbie.aws.loadbalancers.AmazonElasticLoadBalancer
import com.netflix.spinnaker.swabbie.aws.securitygroups.AmazonSecurityGroup
import com.netflix.spinnaker.swabbie.aws.snapshots.AmazonSnapshot
import retrofit.http.GET
import retrofit.http.Path

/**
 * This is an interface to a single edda instance.
 */
interface EddaService {
  // security groups
  @GET("/api/v2/aws/securityGroups/{groupId}")
  fun getSecurityGroup(@Path("groupId") groupId: String): AmazonSecurityGroup

  @GET("/api/v2/aws/securityGroups;_expand")
  fun getSecurityGroups(): List<AmazonSecurityGroup>

  // load balancers
  @GET("/api/v2/aws/loadBalancers;_expand")
  fun getLoadBalancers(): List<AmazonElasticLoadBalancer>

  @GET("/api/v2/aws/loadBalancers/{loadBalancerName}")
  fun getLoadBalancer(@Path("loadBalancerName") loadBalancerName: String): AmazonElasticLoadBalancer

  // auto scaling groups
  @GET("/api/v2/aws/autoScalingGroups/{autoScalingGroupName}")
  fun getAutoScalingGroup(@Path("autoScalingGroupName") autoScalingGroupName: String): AmazonAutoScalingGroup

  @GET("/api/v2/aws/autoScalingGroups;_expand")
  fun getAutoScalingGroups(): List<AmazonAutoScalingGroup>

  @GET("/api/v2/aws/images;_expand")
  fun getImages(): List<AmazonImage>

  @GET("/api/v2/aws/images/{imageId}")
  fun getImage(@Path("imageId") imageId: String): AmazonImage

  @GET("/api/v2/aws/snapshots;_expand")
  fun getSnapshots(): List<AmazonSnapshot>

  @GET("/api/v2/aws/snapshots/{snapshotId}")
  fun getSnapshot(@Path("snapshotId") snapshotId: String): AmazonSnapshot

  @GET("/api/v2/view/instances/{instanceId}")
  fun getInstance(@Path("instanceId") instanceId: String): AmazonInstance

  @GET("/api/v2/view/instances;state.name=running,stopped,starting,rebooting,shutting-down;_expand")
  fun getInstances(): List<AmazonInstance>

  @GET("/api/v2/aws/launchConfigurations;_expand")
  fun getLaunchConfigs(): List<AmazonLaunchConfiguration>

  @GET("/api/v2/aws/launchConfigurations/{launchConfigurationName}")
  fun getLaunchConfig(@Path("launchConfigurationName") launchConfigurationName: String): AmazonLaunchConfiguration

  @GET("/api/v2/aws/launchTemplates;_expand")
  fun getLaunchTemplates(): List<AmazonLaunchTemplate>

  @GET("/api/v2/aws/launchTemplates/{launchTemplateId}")
  fun getLaunchTemplate(@Path("launchTemplateId") launchTemplateId: String): AmazonLaunchTemplate

  @GET("/api/v2/view/launchTemplateVersions;_expand")
  fun getLaunchTemplateVersions(): List<Edda.EddaLaunchTemplaVersion> // TODO: switch to just launchTemplateVersion when edda is updated
}
