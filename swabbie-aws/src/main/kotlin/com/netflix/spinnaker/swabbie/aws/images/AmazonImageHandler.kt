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
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.*
import com.netflix.spinnaker.swabbie.repository.*
import com.netflix.spinnaker.swabbie.tagging.TaggingService
import com.netflix.spinnaker.swabbie.tagging.UpsertImageTagsRequest
import net.logstash.logback.argument.StructuredArguments.kv
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.*
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
  lockingService: Optional<LockingService>,
  retrySupport: RetrySupport,
  private val rules: List<Rule<AmazonImage>>,
  private val imageProvider: ResourceProvider<AmazonImage>,
  private val instanceProvider: ResourceProvider<AmazonInstance>,
  private val launchConfigurationProvider: ResourceProvider<AmazonLaunchConfiguration>,
  private val accountProvider: AccountProvider,
  private val orcaService: OrcaService,
  private val applicationsCaches: List<InMemoryCache<Application>>,
  private val taggingService: TaggingService,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
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
  lockingService,
  retrySupport
) {

  override fun deleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    orcaService.orchestrate(
      OrchestrationRequest(
        // resources are partitioned based on app name, so find app name from first resource
        application = resolveApplicationOrNull(markedResources.first()) ?: "swabbie",
        job = listOf(
          OrcaJob(
            type = "deleteImage",
            context = mutableMapOf(
              "credentials" to workConfiguration.account.name,
              "imageIds" to markedResources.map { it.resourceId }.toSet(),
              "cloudProvider" to AWS,
              "region" to workConfiguration.location,
              "stageTimeoutMs" to Duration.ofMinutes(15).toMillis().toString()
            )
          )
        ),
        description = "Deleting Images :${markedResources.map { it.resourceId }}"
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

  override fun softDeleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    val tags = mapOf("spinnaker:swabbie" to "about_to_be_deleted")
    val taskIds = tagResources(
      "Adding tag to indicate soft deletion",
      Action.SOFTDELETE,
      tags,
      markedResources,
      workConfiguration
    )
    log.debug("Soft deleting resources ${markedResources.map { it.uniqueId() }} in orca tasks $taskIds.")
  }

  override fun restoreResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration) {
    val tags = mapOf("swabbie" to "restored")
    val taskIds = tagResources(
      "Updating tag to indicate restoring",
      Action.RESTORE,
      tags,
      markedResources,
      workConfiguration
    )
    log.debug("Restoring resources ${markedResources.map { it.uniqueId() }} in orca tasks $taskIds.")
  }

  private fun tagResources(
    description: String,
    action: Action,
    tags: Map<String, String>,
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ): List<String> {
    val taskIds = mutableListOf<String>()
    markedResources
      .forEach { resource ->
        val taskId = taggingService.upsertImageTag(
          UpsertImageTagsRequest(
            imageNames = setOf(resource.name ?: resource.resourceId),
            regions = setOf(SwabbieNamespace.namespaceParser(resource.namespace).region),
            tags = tags,
            cloudProvider = "aws",
            cloudProviderType = "aws",
            application = resolveApplicationOrNull(resource) ?: "swabbie",
            description = "$description for image ${resource.uniqueId()}"
          )
        )

        taskTrackingRepository.add(
          taskId,
          TaskCompleteEventInfo(
            action = action,
            markedResources = listOf(resource),
            workConfiguration = workConfiguration,
            submittedTimeMillis = clock.instant().toEpochMilli()
          )
        )
        taskIds.add(taskId)
      }
    return taskIds
  }

  private fun resolveApplicationOrNull(markedResource: MarkedResource): String? {
    val appName = FriggaReflectiveNamer().deriveMoniker(markedResource).app ?: return null
    return if (applicationsCaches.any { it.contains(appName) }) appName else null
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean =
    workConfiguration.resourceType == IMAGE && workConfiguration.cloudProvider == AWS && !rules.isEmpty()

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonImage>? {
    val params = Parameters(
      mapOf(
        "account" to workConfiguration.account.accountId!!,
        "region" to workConfiguration.location,
        "environment" to workConfiguration.account.environment
      )
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
        mapOf(
          "account" to workConfiguration.account.accountId!!,
          "region" to workConfiguration.location,
          "environment" to workConfiguration.account.environment
        )
      )
    )
    return candidates
  }

  /**
   * Checks references for:
   * 1. Instances.
   * 2. Launch Configurations.
   * 3. Image siblings (Image sharing the same name) in other accounts/regions.
   * Bubbles up any raised exception.
   */
  private fun checkReferences(images: List<AmazonImage>?, params: Parameters) {
    if (images == null || images.isEmpty()) {
      return
    }

    log.debug("checking references for {} resources. Parameters: {}", images.size, params)

    images.forEach {
      if (it.name == null || it.description == null) {
        it.set(NAIVE_EXCLUSION, true) // exclude these with exclusions
      }
    }

    val elapsedTimeMillis = measureTimeMillis {
      try {
        setUsedByInstances(images, params)
        setUsedByLaunchConfigurations(images, params)
        setHasSiblings(images, params)
        setSeenWithinUnusedThreshold(images)
      } catch (e: Exception) {
        log.error("Failed to check image references. Params: {}", params, e)
        throw IllegalStateException("Unable to process ${images.size} images. Params: $params", e)
      }
    }

    log.info("Completed checking references for {} images in $elapsedTimeMillis ms. Params: {}", images.size, params)
  }

  /**
   * Checks if images are used by instances
   */
  private fun setUsedByInstances(
    images: List<AmazonImage>,
    params: Parameters
  ) {
    instanceProvider.getAll(params).let { instances ->
      log.info("Checking for references in {} instances", instances?.size)
      if (instances == null || instances.isEmpty()) {
        return
      }

      images.filter {
        NAIVE_EXCLUSION !in it.details && USED_BY_INSTANCES !in it.details
      }.forEach { image ->
        onMatchedImages(
          instances.map { amazonInstance ->
            SlimAwsResource(amazonInstance.name, amazonInstance.imageId, amazonInstance.getAutoscalingGroup().orEmpty())
          },
          image
        ) { usedByResource ->
          image.set(USED_BY_INSTANCES, true)
          resourceUseTrackingRepository.recordUse(image.resourceId, usedByResource)
          log.debug("Image {} ({}) in {} is USED_BY_INSTANCES", image.imageId, image.name, params["region"])
        }
      }
    }
  }

  /**
   * Checks if images are used by launch configurations
   */
  private fun setUsedByLaunchConfigurations(
    images: List<AmazonImage>,
    params: Parameters
  ) {
    launchConfigurationProvider.getAll(params).let { launchConfigurations ->
      log.info("Checking for references in {} launch configurations", launchConfigurations?.size)
      if (launchConfigurations == null || launchConfigurations.isEmpty()) {
        log.debug("No launch configs found for params: {}", params)
        return
      }

      images.filter {
        NAIVE_EXCLUSION !in it.details &&
          USED_BY_INSTANCES !in it.details &&
          USED_BY_LAUNCH_CONFIGURATIONS !in it.details
      }.forEach { image ->
        onMatchedImages(
          launchConfigurations.map { launchConfiguration ->
            SlimAwsResource(
              launchConfiguration.resourceId,
              launchConfiguration.imageId,
              launchConfiguration.getAutoscalingGroupName()
            )
          },
          image
        ) { usedByResource ->
          log.debug("Image {} ({}) in {} is USED_BY_LAUNCH_CONFIGURATIONS", image.imageId, image.name, params["region"])
          resourceUseTrackingRepository.recordUse(image.resourceId, usedByResource)
          image.set(USED_BY_LAUNCH_CONFIGURATIONS, true)
        }
      }
    }
  }

  /**
   * Checks if images have siblings in other accounts
   */
  private fun setHasSiblings(
    images: List<AmazonImage>,
    params: Parameters
  ) {
    log.info("Checking for sibling images.")
    val imagesInOtherAccounts = getImagesFromOtherAccounts(params)

    val otherImagesIdToAccount = imagesInOtherAccounts
      .map { (image, account) ->
        image.imageId to account.accountId
      }.toMap()

    val otherImageDescrToImageId = imagesInOtherAccounts
      .filter { (image, account) ->
        image.description != null
      }.map { (image, account) ->
        image.description!! to image.imageId
      }.toMap()

    val filteredImages = images.filter {
      NAIVE_EXCLUSION !in it.details &&
        USED_BY_INSTANCES !in it.details &&
        USED_BY_LAUNCH_CONFIGURATIONS !in it.details &&
        HAS_SIBLINGS_IN_OTHER_ACCOUNTS !in it.details &&
        IS_BASE_OR_ANCESTOR !in it.details
    }

    val imageDescrToImageId = filteredImages
      .filter { it.description != null }
      .map { it.description!! to it.imageId }
      .toMap()

    filteredImages.forEach { image ->
      if (isAncestor(imageDescrToImageId, image) || isAncestor(otherImageDescrToImageId, image)) {
        image.set(IS_BASE_OR_ANCESTOR, true)
        image.set(NAIVE_EXCLUSION, true)
        log.debug("Image {} ({}) in {} is IS_BASE_OR_ANCESTOR", image.imageId, image.name, params["region"])
      }

      if (otherImagesIdToAccount.containsKey(image.imageId)) {
        image.set(HAS_SIBLINGS_IN_OTHER_ACCOUNTS, true)
        log.debug("Image {} ({}) in {} is HAS_SIBLINGS_IN_OTHER_ACCOUNTS", image.imageId, image.name, params["region"])
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
    val unseenImages: Map<String, String> = resourceUseTrackingRepository
      .getUnused()
      .map {
        it.resourceIdentifier to it.usedByResourceIdentifier
      }.toMap()

    images.filter {
      NAIVE_EXCLUSION !in it.details &&
        USED_BY_INSTANCES !in it.details &&
        USED_BY_LAUNCH_CONFIGURATIONS !in it.details &&
        HAS_SIBLINGS_IN_OTHER_ACCOUNTS !in it.details &&
        IS_BASE_OR_ANCESTOR !in it.details
    }.forEach { image ->
      if (!unseenImages.containsKey(image.imageId)) {
        // Image is not in list of unseen, so we've seen it recently
        //  set that on image
        // If there are no unseen images, this will set the key on every image.
        image.set(SEEN_IN_USE_RECENTLY, true)
        log.debug("Image {} ({}) has been SEEN_IN_USE_RECENTLY", kv("imageId", image.imageId), image.name)
      }
    }
  }

  /**
   * Get images in accounts. Used to check for siblings in those accounts.
   */
  private fun getImagesFromOtherAccounts(
    params: Parameters
  ): List<Pair<AmazonImage, Account>> {
    val result: MutableList<Pair<AmazonImage, Account>> = mutableListOf()
    accountProvider.getAccounts().filter {
      it.type == AWS && it.accountId != params["account"]
    }.forEach { account ->
      account.regions?.forEach { region ->
        log.info("Looking for other images in {}/{}", account.accountId, region)
        imageProvider.getAll(
          Parameters(
            mapOf("account" to account.accountId!!, "region" to region.name, "environment" to account.environment)
          )
        )?.forEach { image ->
          result.add(Pair(image, account))
        }
      }
    }

    return result
  }

  private fun onMatchedImages(ids: List<SlimAwsResource>, image: AmazonImage, onFound: (String) -> Unit) {
    ids.forEach {
      if (image.imageId == it.imageId) {
        onFound.invoke(it.usedByResourceName)
      }
    }
  }

  override fun getCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): AmazonImage? {
    val params = Parameters(
      mapOf(
        "imageId" to resourceId,
        "account" to workConfiguration.account.accountId!!,
        "region" to workConfiguration.location,
        "environment" to workConfiguration.account.environment
      )
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

private data class SlimAwsResource(
  val id: String,
  val imageId: String,
  val usedByResourceName: String
)
