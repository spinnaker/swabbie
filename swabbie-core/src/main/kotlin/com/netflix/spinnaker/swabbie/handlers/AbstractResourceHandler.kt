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

abstract class AbstractResourceHandler(
  private val rules: List<Rule>,
  private val resourceRepository: ResourceRepository,
  private val notifier: Notifier
): ResourceHandler {
  override fun process(markResourceDescription: MarkResourceDescription) {
    try {
      log.info("fetching resources of type {}", markResourceDescription.resourceType)
      val trackedResources: List<TrackedResource>? = resourceRepository.getMarkedResources()
      fetchResources(markResourceDescription)?.forEach { resource ->
        val summaries = mutableListOf(Summary("test", "test rule"))
        rules
          .filter { it.applies(resource) }
          .map { it.apply(resource) }
          .forEach { result ->
            if (result.summary != null) {
              summaries += result.summary
            }
          }

        val alreadyTrackedResource: TrackedResource? = trackedResources?.find { it.resourceId == resource.resourceId }
        if (!summaries.isEmpty()) {
          val owner = "yolo@netflix.com" //TODO: get resource owner
          val terminationTime = 0L //TODO: calculate termination time based on resource type configuration retention policy

          resourceRepository.track(
            TrackedResource(
              resource,
              summaries + (alreadyTrackedResource?.summaries ?: listOf()),
              alreadyTrackedResource?.notification ?: notifier.notify(owner, summaries),
              Math.min(alreadyTrackedResource?.projectedTerminationTime ?: Long.MAX_VALUE, terminationTime)
            ),
            markResourceDescription
          )
        } else {
          if (alreadyTrackedResource != null) {
            log.info("forgetting about {}. Resource is now valid", alreadyTrackedResource)
            resourceRepository.remove(resource.resourceId)
          }
        }
      }
    } catch (e: Exception) {
      log.error("Failed while invoking $javaClass", e)
    }
  }

  private val log: Logger = LoggerFactory.getLogger(javaClass)
}
