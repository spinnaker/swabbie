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

package com.netflix.spinnaker.swabbie.handlers

import com.netflix.spinnaker.swabbie.Notifier
import com.netflix.spinnaker.swabbie.ResourceRepository
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

abstract class AbstractResourceHandler(
  private val rules: List<Rule>,
  private val resourceRepository: ResourceRepository,
  private val notifier: Notifier
): ResourceHandler {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun mark(markResourceDescription: MarkResourceDescription) {
    try {
      log.info("fetching resources of type {}", markResourceDescription.resourceType)
      val markedResources: List<MarkedResource>? = resourceRepository.getMarkedResources()
      val fetchedResources: List<Resource>? = fetchResources(markResourceDescription)
      if (fetchedResources == null || fetchedResources.isEmpty()) {
        log.info("Upstream resources no longer exist. Removing marked resources {}", markedResources)
        markedResources?.forEach {
          resourceRepository.remove(it.resourceId)
        }
      } else {
        fetchedResources.forEach { resource ->
          val summaries = mutableListOf<Summary>()
          rules
            .filter { it.applies(resource) }
            .map { it.apply(resource) }
            .forEach { result ->
              if (result.summary != null) {
                summaries += result.summary
              }
            }

          val alreadyMarkedResource: MarkedResource? = markedResources?.find { it.resourceId == resource.resourceId }
          if (!summaries.isEmpty()) {
            val owner = "yolo@netflix.com" //TODO: get resource owner
            val terminationTime = 0L //TODO: calculate termination time based on resource type configuration retention policy


            // TODO: review the termination time piece
            resourceRepository.track(
              MarkedResource(
                resource,
                summaries + (alreadyMarkedResource?.summaries ?: listOf()),
                alreadyMarkedResource?.notification ?: notifier.notify(owner, summaries),
                TimeUnit.DAYS.toMillis(markResourceDescription.retentionPolicy.days.toLong())
              ),
              markResourceDescription
            )
          } else {
            if (alreadyMarkedResource != null) {
              log.info("forgetting now valid {} resource", alreadyMarkedResource)
              resourceRepository.remove(resource.resourceId)
            }
          }
        }
      }
    } catch (e: Exception) {
      log.error("Failed while invoking $javaClass", e)
    }
  }

  override fun cleanup(markedResource: MarkedResource) {
    fetchResource(markedResource)?.let { resource ->
      val summaries = mutableListOf<Summary>()
      rules
        .filter { it.applies(resource) }
        .map { it.apply(resource) }
        .forEach { result ->
          if (result.summary != null) {
            summaries += result.summary
          }
        }

      when {
        summaries.isEmpty() -> {
          log.info("forgetting now valid {} resource", markedResource)
          resourceRepository.remove(resource.resourceId)
        }
        else -> {
          log.info("Preparing deletion of {}", markedResource)
          doDelete(markedResource)
          resourceRepository.remove(resource.resourceId)
        }
      }
    }
  }

  abstract fun doDelete(markedResource: MarkedResource)
}
