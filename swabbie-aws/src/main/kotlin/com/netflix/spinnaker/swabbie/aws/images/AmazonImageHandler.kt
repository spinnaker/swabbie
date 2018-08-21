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
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.checkStatusDelay
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.io.File
import java.time.Clock
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
  private val applicationsCache: InMemoryCache<Application>
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
  override fun deleteResources(
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ): ReceiveChannel<MarkedResource> = produce {
    orcaService.orchestrate(
      OrchestrationRequest(
        application = resolveApplicationOrNull(markedResources) ?: "swabbie",
        job = listOf(
          OrcaJob(
            type = "deleteImage",
            context = mutableMapOf(
              "credentials" to workConfiguration.account.name,
              "imageIds" to markedResources.map { it.resourceId }.toSet(),
              "cloudProvider" to AWS,
              "region" to workConfiguration.location
            )
          )
        ),
        description = "Deleting Images :${markedResources.map { it.resourceId }}"
      )
    ).let { taskResponse ->
      // "ref": "/tasks/01CK1Y63QFEP4ETC6P5DARECV6"
      val taskId = taskResponse.ref.substring(taskResponse.ref.lastIndexOf("/") + 1)
      var taskStatus = orcaService.getTask(taskId).status
      when {
        taskStatus.isSuccess() -> {
          log.info("Orca Task: {} successfully deleted {} images: {}", taskId, markedResources.size, markedResources)
          markedResources.forEach {
            send(it)
          }
        }

        taskStatus.isFailure() -> throw IncompleteOrFailedDeletionException("Failed to delete $markedResources")
      }

      val deleted = mutableListOf<MarkedResource>()
      for (i in markedResources) {
        try {
          val image: AmazonImage? = getCandidate(i, workConfiguration)
          if (image == null) {
            send(i)
            deleted.add(i)
            continue
          }

          runBlocking {
            val statusCheck = async {
              var candidate: AmazonImage? = null
              while (taskStatus.isIncomplete() && candidate != null) {
                delay(checkStatusDelay)
                taskStatus = orcaService.getTask(taskId).status
                candidate = getCandidate(i, workConfiguration)
                if (taskStatus.isFailure()) {
                  throw IncompleteOrFailedDeletionException()
                }
              }

              if (candidate == null || taskStatus.isSuccess()) {
                send(i)
                deleted.add(i)
              }
            }

            statusCheck.invokeOnCompletion { failure ->
              if (failure != null) {
                throw failure
              }
            }
          }
        } catch (e: Exception) {
          if (e is IncompleteOrFailedDeletionException) {
            log.error("Failed to complete deletion. {} out of {}", deleted, markedResources)
            break
          }

          log.error("failed to delete {}", i)
        }
      }
    }
  }

  class IncompleteOrFailedDeletionException(message: String? = null): RuntimeException(message)

  private fun resolveApplicationOrNull(markedResources: List<MarkedResource>): String? {
    markedResources.find {
      FriggaReflectiveNamer().deriveMoniker(it).app != null &&
        applicationsCache.contains(FriggaReflectiveNamer().deriveMoniker(it).app)
    }?.also {
      return FriggaReflectiveNamer().deriveMoniker(it).app
    }

    return null
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean =
    workConfiguration.resourceType == IMAGE && workConfiguration.cloudProvider == AWS && !rules.isEmpty()

  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonImage>? {
    val params = Parameters(
      mapOf("account" to workConfiguration.account.accountId!!, "region" to workConfiguration.location)
    )

    return imageProvider.getAll(params).also { images ->
      log.info("Got {} images. Checking references", images?.size)
    }
  }

  override fun preProcessCandidates(
    candidates: List<AmazonImage>,
    workConfiguration: WorkConfiguration
  ): List<AmazonImage> {
    log.info("Checking references for {}", candidates.size)
    checkReferences(
      images = candidates,
      params = Parameters(
        mapOf("account" to workConfiguration.account.accountId!!, "region" to workConfiguration.location)
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

    images.forEach {
      if (it.name == null || it.description == null) {
        log.debug("NAIVE_EXCLUSION {}", it)
        it.set(NAIVE_EXCLUSION, true) // exclude these with exclusions
      }
    }

    val elapsedTimeMillis = measureTimeMillis {
      runBlocking {
        listOf(
          setUsedByInstancesAsync(images, params),
          setUsedByLaunchConfigurationsAsync(images, params),
          setHasSiblingsAsync(images, params)
        ).forEach { checkingReferencesTask ->
          try {
            checkingReferencesTask.await()
          } finally {
            if (checkingReferencesTask.isCompletedExceptionally) {
              val throwable = checkingReferencesTask.getCompletionExceptionOrNull()
              log.error("Failed to check images references. Params: {}", params, throwable)
              throw IllegalStateException("Unable to process ${images.size} images. Params: $params", throwable)
            }
          }
        }
      }
    }

    log.info("Completed checking references for {} images in $elapsedTimeMillis ms. Params: {}", images.size, params)
  }

  /**
   * Checks if images are used by instances
   */
  private fun setUsedByInstancesAsync(
    images: List<AmazonImage>,
    params: Parameters
  ): Deferred<Unit> = async {
    instanceProvider.getAll(params).let { instances ->
      log.info("Checking for references in {} instances", instances?.size)
      if (instances == null || instances.isEmpty()) {
        return@async
      }

      images.filter {
        USED_BY_INSTANCES !in it.details
      }.forEach { image ->
        onMatchedImages(instances.map { it.imageId }, image) {
          log.debug("USED_BY_INSTANCES {}", image)
          image.set(USED_BY_INSTANCES, true)
        }
      }
    }
  }

  /**
   * Checks if images are used by launch configurations
   */
  private fun setUsedByLaunchConfigurationsAsync(
    images: List<AmazonImage>,
    params: Parameters
  ): Deferred<Unit> = async {
    launchConfigurationProvider.getAll(params).let { launchConfigurations ->
      log.info("Checking for references in {} launch configurations", launchConfigurations?.size)
      if (launchConfigurations == null || launchConfigurations.isEmpty()) {
        return@async
      }

      images.filter {
        USED_BY_LAUNCH_CONFIGURATIONS !in it.details
      }.forEach { image ->
        onMatchedImages(launchConfigurations.map { it.imageId }, image) {
          log.debug("USED_BY_LAUNCH_CONFIGURATIONS {}", image)
          image.set(USED_BY_LAUNCH_CONFIGURATIONS, true)
        }
      }
    }
  }

  /**
   * Checks if images have siblings in other accounts
   */
  private fun setHasSiblingsAsync(
    images: List<AmazonImage>,
    params: Parameters
  ) = async {
    log.info("Checking for sibling images.")
    val imagesInOtherAccounts = getImagesFromOtherAccounts(params)
    images.filter {
      HAS_SIBLINGS_IN_OTHER_ACCOUNTS !in it.details && NAIVE_EXCLUSION !in it.details && IS_BASE_OR_ANCESTOR !in it.details
    }.forEach { image ->
      async {
        if (images.any { image.isAncestorOf(it) && image != it }) {
          image.set(IS_BASE_OR_ANCESTOR, true)
          image.set(NAIVE_EXCLUSION, true)
          log.debug("IS_BASE_OR_ANCESTOR  NAIVE_EXCLUSION {}", image)
        }

        for (pair in imagesInOtherAccounts) {
          if (image.isAncestorOf(pair.first)) {
            log.debug("IS_BASE_OR_ANCESTOR NAIVE_EXCLUSION {}", image)
            image.set(IS_BASE_OR_ANCESTOR, true)
            image.set(NAIVE_EXCLUSION, true)
            break
          }

          if (pair.first.matches(image)) {
            log.debug("HAS_SIBLINGS_IN_OTHER_ACCOUNTS {}", image)
            image.set(HAS_SIBLINGS_IN_OTHER_ACCOUNTS, true)
            break
          }
        }
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
          Parameters(mapOf("account" to account.accountId!!, "region" to region.name))
        )?.forEach { image ->
          result.add(Pair(image, account))
        }
      }
    }

    return result
  }

  private fun onMatchedImages(ids: List<String>, image: AmazonImage, onFound: () -> Unit) {
    ids.forEach {
      if (image.imageId == it) {
        onFound.invoke()
      }
    }
  }

  override fun getCandidate(markedResource: MarkedResource, workConfiguration: WorkConfiguration): AmazonImage? {
    return imageProvider.getOne(
      Parameters(mapOf(
        "imageId" to markedResource.resourceId,
        "account" to workConfiguration.account.accountId!!,
        "region" to workConfiguration.location)
      )
    )
  }
}

// TODO: specific to netflix pattern. make generic
private fun AmazonImage.isAncestorOf(image: AmazonImage): Boolean {
  return image.description != null && (image.description.contains("ancestor_id=${this.imageId}")
    || image.description.contains("ancestor_name=${this.name}"))
}

private fun AmazonImage.matches(image: AmazonImage): Boolean {
  return name == image.name || imageId == image.imageId
}
