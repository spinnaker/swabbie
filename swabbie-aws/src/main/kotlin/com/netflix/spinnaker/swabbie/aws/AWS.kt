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

import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.ClientConfiguration
import com.amazonaws.SdkBaseException
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider
import com.amazonaws.retry.PredefinedRetryPolicies.DEFAULT_BACKOFF_STRATEGY
import com.amazonaws.retry.PredefinedRetryPolicies.DEFAULT_MAX_ERROR_RETRY
import com.amazonaws.retry.RetryPolicy
import com.amazonaws.retry.RetryUtils
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.DescribeImagesResult
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.DescribeInstancesResult
import com.amazonaws.services.ec2.model.DescribeLaunchTemplateVersionsRequest
import com.amazonaws.services.ec2.model.DescribeLaunchTemplateVersionsResult
import com.amazonaws.services.ec2.model.DescribeLaunchTemplatesRequest
import com.amazonaws.services.ec2.model.DescribeLaunchTemplatesResult
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult
import com.amazonaws.services.ec2.model.DescribeSnapshotsRequest
import com.amazonaws.services.ec2.model.DescribeSnapshotsResult
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClientBuilder
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult
import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.aws.launchtemplates.AmazonLaunchTemplate
import com.netflix.spinnaker.swabbie.aws.launchtemplates.AmazonLaunchTemplateVersion
import com.netflix.spinnaker.swabbie.aws.loadbalancers.AmazonElasticLoadBalancer
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.aws.securitygroups.AmazonSecurityGroup
import com.netflix.spinnaker.swabbie.aws.snapshots.AmazonSnapshot
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * AWS reads API
 */
interface AWS {
  fun getInstances(params: Parameters): List<AmazonInstance>
  fun getInstance(params: Parameters): AmazonInstance?
  fun getLaunchConfigurations(params: Parameters): List<AmazonLaunchConfiguration>
  fun getLaunchConfiguration(params: Parameters): AmazonLaunchConfiguration?
  fun getLaunchTemplates(params: Parameters): List<AmazonLaunchTemplate>
  fun getLaunchTemplate(params: Parameters): AmazonLaunchTemplate?
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
  fun getLaunchTemplateVersions(params: Parameters): List<AmazonLaunchTemplateVersion>
}

/**
 * AWS api impl
 */
class Vanilla(
  private val sts: AWSSecurityTokenService,
  private val objectMapper: ObjectMapper,
  accountProvider: AccountProvider
) : AWS {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun getInstances(params: Parameters): List<AmazonInstance> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val instances = mutableListOf<Instance>()
    val request = DescribeInstancesRequest()
      .withMaxResults(instanceMaxResult)
      .withFilters(instanceStateFilter)

    var result: DescribeInstancesResult = account.ec2(params.region)
      .describeInstances(request)
    instances.addAll(result.reservations.map { it.instances }.flatten())

    var nextToken = result.nextToken
    while (!nextToken.isNullOrBlank()) {
      request.nextToken = nextToken
      result = account.ec2(params.region).describeInstances(request)
      instances.addAll(result.reservations.map { it.instances }.flatten())
      nextToken = result.nextToken
    }

    return convert(instances)
  }

  override fun getInstance(params: Parameters): AmazonInstance? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeInstancesRequest().withInstanceIds(params.id)
    val result = account.ec2(params.region).describeInstances(request)
    val tmp = result.reservations.map { it.instances }.flatten()
    return convert<AmazonInstance>(tmp).first()
  }

  override fun getLaunchConfigurations(params: Parameters): List<AmazonLaunchConfiguration> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val launchConfigurations = mutableListOf<AmazonLaunchConfiguration>()
    val request = DescribeLaunchConfigurationsRequest()
      .withMaxRecords(launchConfigurationsMaxResult)
    var result: DescribeLaunchConfigurationsResult = account.autoScaling(params.region)
      .describeLaunchConfigurations(request)

    launchConfigurations.addAll(
      convert(result.launchConfigurations)
    )

    var nextToken = result.nextToken
    while (!nextToken.isNullOrBlank()) {
      request.nextToken = nextToken
      result = account.autoScaling(params.region)
        .describeLaunchConfigurations(request)

      launchConfigurations.addAll(
        convert(result.launchConfigurations)
      )

      nextToken = result.nextToken
    }

    return launchConfigurations
  }

  override fun getLaunchConfiguration(params: Parameters): AmazonLaunchConfiguration? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeLaunchConfigurationsRequest().withLaunchConfigurationNames(params.id)
    val result = account.autoScaling(params.region).describeLaunchConfigurations(request)
    return convert<AmazonLaunchConfiguration>(result.launchConfigurations).first()
  }

  override fun getLaunchTemplates(params: Parameters): List<AmazonLaunchTemplate> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val launchTemplates: MutableList<AmazonLaunchTemplate> = ArrayList()
    val request = DescribeLaunchTemplatesRequest()
    while (true) {
      val result: DescribeLaunchTemplatesResult = account.ec2(params.region).describeLaunchTemplates(request)
      launchTemplates.addAll(
        convert(result.launchTemplates)
      )
      if (result.nextToken != null) {
        request.withNextToken(result.nextToken)
      } else {
        break
      }
    }

    return launchTemplates
  }

  override fun getLaunchTemplate(params: Parameters): AmazonLaunchTemplate? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeLaunchTemplatesRequest().withLaunchTemplateIds(params.id)
    val result = account.ec2(params.region).describeLaunchTemplates(request)
    return convert<AmazonLaunchTemplate>(result.launchTemplates).first()
  }

  override fun getLaunchTemplateVersions(params: Parameters): List<AmazonLaunchTemplateVersion> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val launchTemplateVersions: MutableList<AmazonLaunchTemplateVersion> = ArrayList()
    val request = DescribeLaunchTemplateVersionsRequest().withVersions("\$Latest", "\$Default")
    while (true) {
      val result: DescribeLaunchTemplateVersionsResult = account.ec2(params.region).describeLaunchTemplateVersions(request)
      launchTemplateVersions.addAll(
        convert(result.launchTemplateVersions)
      )
      if (result.nextToken != null) {
        request.withNextToken(result.nextToken)
      } else {
        break
      }
    }

    return launchTemplateVersions
  }

  override fun getServerGroups(params: Parameters): List<AmazonAutoScalingGroup> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val autoScalingGroups = mutableListOf<AmazonAutoScalingGroup>()
    val request = DescribeAutoScalingGroupsRequest().withMaxRecords(serverGroupMaxResult)
    var result: DescribeAutoScalingGroupsResult = account.autoScaling(params.region)
      .describeAutoScalingGroups()
    autoScalingGroups.addAll(
      convert(result.autoScalingGroups)
    )

    var nextToken = result.nextToken
    while (!nextToken.isNullOrBlank()) {
      request.nextToken = nextToken
      result = account.autoScaling(params.region)
        .describeAutoScalingGroups(request)

      autoScalingGroups.addAll(
        convert(result.autoScalingGroups)
      )

      nextToken = result.nextToken
    }

    return autoScalingGroups
  }

  override fun getServerGroup(params: Parameters): AmazonAutoScalingGroup? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(params.id)
    val result = account.autoScaling(params.region).describeAutoScalingGroups(request)
    return convert<AmazonAutoScalingGroup>(result.autoScalingGroups).first()
  }

  override fun getElasticLoadBalancers(params: Parameters): List<AmazonElasticLoadBalancer> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val elbList = mutableListOf<AmazonElasticLoadBalancer>()
    var result: DescribeLoadBalancersResult = account.elasticLoadBalancing(params.region)
      .describeLoadBalancers()
    elbList.addAll(
      convert(result.loadBalancerDescriptions)
    )

    var nextToken = result.nextMarker
    while (!nextToken.isNullOrBlank()) {
      result = account.elasticLoadBalancing(params.region)
        .describeLoadBalancers()
        .withNextMarker(nextToken)

      elbList.addAll(
        convert(result.loadBalancerDescriptions)
      )

      nextToken = result.nextMarker
    }

    return elbList
  }

  override fun getElasticLoadBalancer(params: Parameters): AmazonElasticLoadBalancer? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeLoadBalancersRequest(listOf(params.id))
    val result = account.elasticLoadBalancing(params.region).describeLoadBalancers(request)
    return convert<AmazonElasticLoadBalancer>(result.loadBalancerDescriptions).first()
  }

  override fun getImages(params: Parameters): List<AmazonImage> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val images = mutableSetOf<AmazonImage>()
    // AWS api doesnt support pagination, breaking down the list because this call can be very expensive
    imageFilters.forEach {
      log.info("Getting Images with filter {}", it)
      val request = DescribeImagesRequest()
        .withFilters(Filter("is-public").withValues("false"))
        .withFilters(it)
      val result: DescribeImagesResult = account.ec2(params.region).describeImages(request)
      images.addAll(convert(result.images))
    }

    return images.toList()
  }

  override fun getImage(params: Parameters): AmazonImage? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeImagesRequest().withImageIds(params.id)
    val result: DescribeImagesResult = account.ec2(params.region).describeImages(request)
    return convert<AmazonImage>(result.images).first()
  }

  override fun getSecurityGroups(params: Parameters): List<AmazonSecurityGroup> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val groups = mutableListOf<AmazonSecurityGroup>()
    var result: DescribeSecurityGroupsResult = account.ec2(params.region)
      .describeSecurityGroups()
    groups.addAll(
      convert(result.securityGroups)
    )

    var nextToken = result.nextToken
    while (!nextToken.isNullOrBlank()) {
      result = account.ec2(params.region)
        .describeSecurityGroups()
        .withNextToken(nextToken)

      groups.addAll(
        convert(result.securityGroups)
      )

      nextToken = result.nextToken
    }

    return groups
  }

  override fun getSecurityGroup(params: Parameters): AmazonSecurityGroup? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeSecurityGroupsRequest().withGroupIds(params.id)
    val result: DescribeSecurityGroupsResult = account.ec2(params.region).describeSecurityGroups(request)
    return convert<AmazonSecurityGroup>(result.securityGroups).first()
  }

  override fun getSnapshots(params: Parameters): List<AmazonSnapshot> {
    val account: SpinnakerAccount = findAccount(params) ?: return emptyList()
    val snapshots = mutableListOf<AmazonSnapshot>()
    val request = DescribeSnapshotsRequest()
      .withMaxResults(snapshotsMaxResult)
      .withFilters(Filter("owner-id").withValues(account.accountId))
    var result: DescribeSnapshotsResult = account.ec2(params.region)
      .describeSnapshots(request)

    snapshots.addAll(
      convert(result.snapshots)
    )

    var nextToken = result.nextToken
    while (!nextToken.isNullOrBlank()) {
      request.nextToken = nextToken
      result = account.ec2(params.region)
        .describeSnapshots(request)

      snapshots.addAll(
        convert(result.snapshots)
      )

      nextToken = result.nextToken
    }

    return snapshots
  }

  override fun getSnapshot(params: Parameters): AmazonSnapshot? {
    val account: SpinnakerAccount = findAccount(params) ?: return null
    val request = DescribeSnapshotsRequest().withSnapshotIds(params.id)
    val result = account.ec2(params.region).describeSnapshots(request)
    return convert<AmazonSnapshot>(result.snapshots).first()
  }

  private val snapshotsMaxResult = 1000
  private val instanceMaxResult = 1000
  private val launchConfigurationsMaxResult = 100
  private val serverGroupMaxResult = 100

  private val instanceStateFilter = listOf(
    Filter("instance-state-name").withValues(listOf("starting", "rebooting", "stopped", "running", "shutting-down"))
  )

  private val imageFilters = listOf(
    Filter("state").withValues("available"),
    Filter("state").withValues("pending"),
    Filter("state").withValues("failed")
  )

  private val clientConfiguration = clientConfiguration()
  private val accounts = accountProvider.getAccounts().filter { it.cloudProvider == "aws" }
  private val credentials = CacheBuilder.newBuilder()
    .expireAfterAccess(50, TimeUnit.MINUTES)
    .build(object : CacheLoader<SpinnakerAccount, STSAssumeRoleSessionCredentialsProvider>() {
      override fun load(account: SpinnakerAccount): STSAssumeRoleSessionCredentialsProvider {
        return STSAssumeRoleSessionCredentialsProvider
          .Builder("arn:aws:iam::${account.accountId}:${account.assumeRole}", account.sessionName)
          .withStsClient(sts)
          .build()
      }
    })

  private fun SpinnakerAccount.autoScaling(region: String): AmazonAutoScaling {
    return AmazonAutoScalingClientBuilder
      .standard()
      .withRegion(region)
      .withClientConfiguration(clientConfiguration)
      .withCredentials(credentials.get(this))
      .build()
  }

  private fun SpinnakerAccount.ec2(region: String): AmazonEC2 {
    return AmazonEC2ClientBuilder
      .standard()
      .withRegion(region)
      .withClientConfiguration(clientConfiguration)
      .withCredentials(credentials.get(this))
      .build()
  }

  private fun SpinnakerAccount.elasticLoadBalancing(region: String): AmazonElasticLoadBalancing {
    return AmazonElasticLoadBalancingClientBuilder
      .standard()
      .withRegion(region)
      .withClientConfiguration(clientConfiguration)
      .withCredentials(credentials.get(this))
      .build()
  }

  private fun SpinnakerAccount.matchesRegion(region: String) =
    !regions.isNullOrEmpty() && regions!!.any { it.name == region }

  private fun findAccount(params: Parameters): SpinnakerAccount? {
    val account = accounts.find {
      it.accountId == params.account
    } as? SpinnakerAccount

    if (account == null || !account.matchesRegion(params.region)) {
      return null
    }

    return account
  }

  private fun clientConfiguration(): ClientConfiguration {
    return ClientConfiguration()
      .withRetryPolicy(
        RetryPolicy(
          AWSRetryCondition(), DEFAULT_BACKOFF_STRATEGY, DEFAULT_MAX_ERROR_RETRY, true
        )
      ).withRequestTimeout(Duration.ofMinutes(5).toMillis().toInt())
  }

  private inline fun <reified T : AmazonResource> convert(obj: Any): List<T> {
    if (obj is List<*>) {
      return obj.map {
        objectMapper.convertValue(it, T::class.java)
      }
    }

    return listOf(objectMapper.convertValue(obj, T::class.java))
  }

  class AWSRetryCondition : RetryPolicy.RetryCondition {
    override fun shouldRetry(
      originalRequest: AmazonWebServiceRequest,
      exception: AmazonClientException,
      retriesAttempted: Int
    ): Boolean {
      if (exception.cause is IOException) {
        return true
      }

      if (exception is AmazonServiceException) {
        if (RetryUtils.isRetryableServiceException(SdkBaseException(exception))) {
          return true
        }

        if (RetryUtils.isThrottlingException(SdkBaseException(exception))) {
          return true
        }

        if (RetryUtils.isClockSkewError(SdkBaseException(exception))) {
          return true
        }
      }
      return false
    }
  }
}
