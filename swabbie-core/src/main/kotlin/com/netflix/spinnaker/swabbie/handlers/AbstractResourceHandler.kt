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
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

abstract class AbstractResourceHandler(
  private val rules: List<Rule>,
  private val resourceRepository: ResourceRepository,
  private val notifier: Notifier
): ResourceHandler {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun mark(workConfiguration: WorkConfiguration) {
    try {
      log.info("fetching resources of type {}", workConfiguration.resourceType)
      val markedResources: List<MarkedResource>? = resourceRepository.getMarkedResources()
        ?.filter {
          it.resourceType == workConfiguration.resourceType
        }

      fetchResources(workConfiguration).let { resources ->
        if (resources == null || resources.isEmpty()) {
          log.info("Upstream resources no longer exist. Removing marked resources {}", markedResources)
          markedResources?.forEach {
            resourceRepository.remove(it.resourceId)
          }
        } else {
          log.info("fetched {} resources of type {}", resources.size, workConfiguration.resourceType)
          resources.forEach { resource ->
            val summaries = mutableListOf<Summary>()
//            summaries += Summary("failed", "testRule") //TODO: remove
            rules
              .filter { it.applies(resource) }
              .map { it.apply(resource) }
              .map { result ->
                if (result.summary != null) {
                  summaries += result.summary
                }
              }

            val alreadyMarkedResource: MarkedResource? = markedResources?.find { it.resourceId == resource.resourceId }
            if (!summaries.isEmpty()) {
              log.info("found cleanup candidate {} of type {}, summaries {}", resource, workConfiguration.resourceType, summaries)
              val owner = "yolo@netflix.com" //TODO: get resource owner
              val terminationTime = LocalDateTime.now().plusDays((workConfiguration.retention.days + workConfiguration.retention.ageThresholdDays).toLong())

              resourceRepository.track(
                MarkedResource(
                  resource,
                  summaries + (alreadyMarkedResource?.summaries ?: listOf()),
                  alreadyMarkedResource?.notification ?: notifier.notify(owner, summaries),
                  terminationTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                  workConfiguration.configurationId
                )
              )
            } else {
              if (alreadyMarkedResource != null) {
                log.info("forgetting now valid {} resource", alreadyMarkedResource)
                resourceRepository.remove(resource.resourceId)
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      log.error("Failed while invoking $javaClass", e)
    }
  }

  override fun clean(markedResource: MarkedResource) {
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
          // TODO: should also check when user got notified and test for that
          if (markedResource.projectedTerminationTime.toLocalDate().isBefore(LocalDate.now())) {
            doDelete(markedResource)
          }

          resourceRepository.remove(resource.resourceId)
        }
      }
    }
  }

  private fun Long.toLocalDate(): LocalDate {
    return Instant.ofEpochMilli(this)
      .atZone(ZoneId.systemDefault())
      .toLocalDate()
  }

  abstract fun doDelete(markedResource: MarkedResource)
}
