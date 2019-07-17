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

package com.netflix.spinnaker.swabbie.aws.images

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.AbstractResourceTypeHandler
import com.netflix.spinnaker.swabbie.InMemorySingletonCache
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.ResourceProvider
import com.netflix.spinnaker.swabbie.aws.edda.providers.AmazonImagesUsedByInstancesCache
import com.netflix.spinnaker.swabbie.aws.edda.providers.AmazonLaunchConfigurationCache
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exception.CacheSizeException
import com.netflix.spinnaker.swabbie.exception.StaleCacheException
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.IMAGE
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.NAIVE_EXCLUSION
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import com.netflix.spinnaker.swabbie.orca.generateWaitStageWithRandWaitTime
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import net.logstash.logback.argument.StructuredArguments.kv
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import kotlin.system.measureTimeMillis

@Component
class AmazonImageHandler(
  registry: Registry,
  clock: Clock,
  notifiers: List<Notifier>,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonImage>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  retrySupport: RetrySupport,
  dynamicConfigService: DynamicConfigService,
  private val launchConfigurationCache: InMemorySingletonCache<AmazonLaunchConfigurationCache>,
  private val imagesUsedByinstancesCache: InMemorySingletonCache<AmazonImagesUsedByInstancesCache>,
  private val rules: List<Rule<AmazonImage>>,
  private val imageProvider: ResourceProvider<AmazonImage>,
  private val orcaService: OrcaService,
  private val applicationUtils: ApplicationUtils,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
  private val usedResourceRepository: UsedResourceRepository,
  private val swabbieProperties: SwabbieProperties
) : AbstractResourceTypeHandler<AmazonImage>(
  registry,
  clock,
  rules,
  resourceTrackingRepository,
  resourceStateRepository,
  exclusionPolicies,
  resourceOwnerResolver,
  notifiers,
  applicationEventPublisher,
  retrySupport,
  resourceUseTrackingRepository,
  swabbieProperties,
  dynamicConfigService
) {

  @Value("\${swabbie.clean.jitter-interval:600}")
  private var cleanInterval: Long = 600

  override fun deleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    orcaService.orchestrate(
      OrchestrationRequest(
        // resources are partitioned based on grouping, so find the app to use from first resource
        application = applicationUtils.determineApp(markedResources.first().resource),
        job = listOf(
          generateWaitStageWithRandWaitTime(cleanInterval),
          OrcaJob(
            type = "deleteImage",
            context = mutableMapOf(
              "credentials" to workConfiguration.account.name,
              "imageIds" to markedResources.map { it.resourceId }.toSet(),
              "cloudProvider" to AWS,
              "region" to workConfiguration.location,
              "requisiteStageRefIds" to listOf("0")
            )
          )
        ),
        description = "Deleting Images: ${markedResources.map { it.resourceId }}"
      )
    ).let { taskResponse ->
      taskTrackingRepository.add(
        taskResponse.taskId(),
        TaskCompleteEventInfo(
          action = Action.DELETE,
          markedResources = markedResources,
          workConfiguration = workConfiguration,
          submittedTimeMillis = clock.instant().toEpochMilli()
        )
      )
      log.debug("Deleting resources ${markedResources.map { it.uniqueId() }} in orca task ${taskResponse.taskId()}")
    }
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean =
    workConfiguration.resourceType == IMAGE && workConfiguration.cloudProvider == AWS && !rules.isEmpty()

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonImage>? {
    val params = Parameters(
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
    )

    return imageProvider.getAll(params).also { images ->
      log.info("Got {} images.", images?.size)
    }
  }

  override fun preProcessCandidates(
    candidates: List<AmazonImage>,
    workConfiguration: WorkConfiguration
  ): List<AmazonImage> {
    checkReferences(
      images = candidates,
      params = Parameters(
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
      )
    )
    return candidates
  }

  /**
   * Checks references for:
   * 1. Instances.
   * 2. Launch Configurations.
   * 3. Seen in use recently.
   * Bubbles up any raised exception.
   */
  private fun checkReferences(images: List<AmazonImage>?, params: Parameters) {
    if (images == null || images.isEmpty()) {
      return
    }

    log.debug("checking references for {} resources. Parameters: {}", images.size, params)

    images.forEach { image ->
      if (image.name == null || image.description == null) {
        image.set(NAIVE_EXCLUSION, true) // exclude these with exclusions
      }

      if (image.blockDeviceMappings != null) {
        image.blockDeviceMappings.forEach { blockDevice ->
          if (blockDevice.ebs != null && blockDevice.ebs.snapshotId != null) {
            usedResourceRepository.recordUse(SNAPSHOT, blockDevice.ebs.snapshotId, "aws:${params.region}:${params.account}")
          }
        }
      }
    }

    val elapsedTimeMillis = measureTimeMillis {
      try {
        setUsedByLaunchConfigurations(images, params)
        setUsedByInstances(images, params)
        setSeenWithinUnusedThreshold(images)
      } catch (e: Exception) {
        log.error("Failed to check image references. Params: {}", params, e)
        throw IllegalStateException("Unable to process ${images.size} images. Params: $params", e)
      }
    }

    log.info("Completed checking references for {} images in $elapsedTimeMillis ms. Params: {}", images.size, params)
  }

  /**
   * Checks if images are used by launch configurations in all accounts
   */
  private fun setUsedByLaunchConfigurations(
    images: List<AmazonImage>,
    params: Parameters
  ) {
    if (launchConfigurationCache.get().getLastUpdated()
        < clock.instant().toEpochMilli().minus(Duration.ofHours(1).toMillis())) {
      throw StaleCacheException("Amazon launch configuration cache over 1 hour old, aborting.")
    }
    val imagesUsedByLaunchConfigsForRegion = launchConfigurationCache.get().getRefdAmisForRegion(params.region).keys
    log.info("Checking the {} images used by launch configurations.", imagesUsedByLaunchConfigsForRegion.size)
    if (imagesUsedByLaunchConfigsForRegion.size < swabbieProperties.minImagesUsedByLC) {
      throw CacheSizeException("Amazon launch configuration cache contains less than " +
        "${swabbieProperties.minImagesUsedByLC} images used, aborting for safety.")
    }

    images
      .filter { NAIVE_EXCLUSION !in it.details }
      .forEach { image ->
        if (imagesUsedByLaunchConfigsForRegion.contains(image.imageId)) {
          log.debug("Image {} ({}) in {} is USED_BY_LAUNCH_CONFIGS", image.imageId, image.name, params.region)
          image.set(USED_BY_LAUNCH_CONFIGURATIONS, true)

          val usedByLaunchConfigs = launchConfigurationCache
            .get().getLaunchConfigsByRegionForImage(params.copy(id = image.imageId))
            .joinToString(",") { it.getAutoscalingGroupName() }

          resourceUseTrackingRepository.recordUse(
            image.resourceId,
            usedByLaunchConfigs
          )
        }
      }
  }

  /**
   * Checks if images are used by instances
   */
  private fun setUsedByInstances(
    images: List<AmazonImage>,
    params: Parameters
  ) {
    if (imagesUsedByinstancesCache.get().getLastUpdated()
        < clock.instant().toEpochMilli().minus(Duration.ofHours(1).toMillis())) {
      throw StaleCacheException("Amazon images used by instances cache over 1 hour old, aborting.")
    }
    val imagesUsedByInstancesInRegion = imagesUsedByinstancesCache.get().getAll(params)
    log.info("Checking {} images used by instances", imagesUsedByInstancesInRegion.size)
    if (imagesUsedByInstancesInRegion.size < swabbieProperties.minImagesUsedByInst) {
      throw CacheSizeException("Amazon images used by instances cache contains less than " +
        "${swabbieProperties.minImagesUsedByInst} images, aborting for safety.")
    }

    images
      .filter { NAIVE_EXCLUSION !in it.details && USED_BY_LAUNCH_CONFIGURATIONS !in it.details }
      .forEach { image ->
        if (imagesUsedByInstancesInRegion.contains(image.imageId)) {
          log.debug("Image {} ({}) in {} is USED_BY_INSTANCES", image.imageId, image.name, params.region)
          image.set(USED_BY_INSTANCES, true)

          resourceUseTrackingRepository.recordUse(
            image.resourceId,
            "Used by an instance."
          )
        }
      }
  }

  /**
   * Checks if an image has been seen in use recently.
   */
  private fun setSeenWithinUnusedThreshold(images: List<AmazonImage>) {
    log.info("Checking for images that haven't been seen in more than ${swabbieProperties.outOfUseThresholdDays} days")
    if (swabbieProperties.outOfUseThresholdDays == 0) {
      log.info("Bypassing seen in use check, since `swabbieProperties.outOfUseThresholdDays` is 0")
      return
    }
    val usedImages = resourceUseTrackingRepository.getUsed()
    val unusedAndTracked: Map<String, String> = resourceUseTrackingRepository
      .getUnused()
      .map {
        it.resourceId to it.usedByResourceId
        }.toMap()

    images.filter {
      NAIVE_EXCLUSION !in it.details &&
        USED_BY_INSTANCES !in it.details &&
        USED_BY_LAUNCH_CONFIGURATIONS !in it.details &&
        IS_BASE_OR_ANCESTOR !in it.details
    }.forEach { image ->
      if (!unusedAndTracked.containsKey(image.imageId) && usedImages.contains(image.imageId)) {
        // Image has not been unused for the outOfUseThreshold, and has been seen before
        image.set(SEEN_IN_USE_RECENTLY, true)
        log.debug("Image {} ({}) has been SEEN_IN_USE_RECENTLY", kv("imageId", image.imageId), image.name)
      }
    }
  }

  override fun getCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): AmazonImage? {
    val params = Parameters(
        id = resourceId,
        account = workConfiguration.account.accountId!!,
        region = workConfiguration.location,
        environment = workConfiguration.account.environment
    )

    return imageProvider.getOne(params)
  }
}

// TODO: specific to netflix pattern. make generic
private fun isAncestor(images: Map<String, String>, image: AmazonImage): Boolean {
  return images.containsKey("ancestor_id=${image.imageId}") || images.containsKey("ancestor_name=${image.name}")
}

private fun AmazonImage.matches(image: AmazonImage): Boolean {
  return name == image.name || imageId == image.imageId
}
