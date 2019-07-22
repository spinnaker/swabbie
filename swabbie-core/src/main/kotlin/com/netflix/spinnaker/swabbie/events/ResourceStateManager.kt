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

package com.netflix.spinnaker.swabbie.events

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.MetricsSupport
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.SwabbieNamespace
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.model.humanReadableDeletionTime
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import com.netflix.spinnaker.swabbie.tagging.TaggingService
import com.netflix.spinnaker.swabbie.tagging.UpsertImageTagsRequest
import com.netflix.spinnaker.swabbie.tagging.UpsertServerGroupTagsRequest
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ResourceStateManager(
  private val resourceStateRepository: ResourceStateRepository,
  private val clock: Clock,
  private val registry: Registry,
  @Autowired(required = false) private val resourceTagger: ResourceTagger?,
  private val taggingService: TaggingService,
  private val taskTrackingRepository: TaskTrackingRepository,
  private val applicationUtils: ApplicationUtils
) : MetricsSupport(registry) {

  private val log = LoggerFactory.getLogger(javaClass)

  @EventListener
  fun handleEvents(event: Event) {
    var id: Id? = null
    var msg: String? = null
    var removeTag = false

    when (event) {
      is MarkResourceEvent -> {
        id = markCountId
        msg = "${event.markedResource.typeAndName()} scheduled to be cleaned up on " +
          "${event.markedResource.humanReadableDeletionTime(clock)}"
      }

      is UnMarkResourceEvent -> {
        id = unMarkCountId
        removeTag = true
        msg = "${event.markedResource.typeAndName()}. No longer a cleanup candidate"
      }

      is OwnerNotifiedEvent -> {
        id = notifyCountId
        removeTag = false
        msg = "Notified ${event.markedResource.notificationInfo?.recipient} about soon to be cleaned up " +
          event.markedResource.typeAndName()
      }

      is OptOutResourceEvent -> {
        id = optOutCountId
        removeTag = true
        msg = "${event.markedResource.typeAndName()}. Opted Out"
      }

      is DeleteResourceEvent -> {
        id = deleteCountId
        removeTag = true
        msg = "Removing tag for now deleted ${event.markedResource.typeAndName()}"
      }

      is OrcaTaskFailureEvent -> {
        id = orcaTaskFailureId
        removeTag = false
        msg = generateFailureMessage(event)
        // todo eb: do we want this tagged here?
      }

      else -> log.warn("Unknown event type: ${event.javaClass.simpleName}")
    }

    updateState(event)
    id?.let {
      registry.counter(
        it.withTags(
          "configuration", event.workConfiguration.namespace,
          "resourceType", event.workConfiguration.resourceType
        )
      ).increment()
    }

    if (resourceTagger != null && msg != null) {
      tag(resourceTagger, event, msg, removeTag)
    }
  }

  fun generateFailureMessage(event: Event) =
    "Task failure for action ${event.action} on resource ${event.markedResource.typeAndName()}"

  private fun tag(tagger: ResourceTagger, event: Event, msg: String, remove: Boolean = false) {
    if (!remove) {
      tagger.tag(
        markedResource = event.markedResource,
        workConfiguration = event.workConfiguration,
        description = msg
      )
    } else {
      tagger.unTag(
        markedResource = event.markedResource,
        workConfiguration = event.workConfiguration,
        description = msg
      )
    }
  }

  private fun updateState(event: Event) {
    val currentState = resourceStateRepository.get(
      resourceId = event.markedResource.resourceId,
      namespace = event.markedResource.namespace
    )
    val statusName = if (event is OrcaTaskFailureEvent) "${event.action.name} FAILED" else event.action.name
    val status = Status(statusName, clock.instant().toEpochMilli())

    currentState?.statuses?.add(status)
    val newState = (currentState?.copy(
      statuses = currentState.statuses,
      markedResource = event.markedResource,
      deleted = event is DeleteResourceEvent,
      optedOut = event is OptOutResourceEvent,
      currentStatus = status
    ) ?: ResourceState(
      markedResource = event.markedResource,
      deleted = event is DeleteResourceEvent,
      optedOut = event is OptOutResourceEvent,
      statuses = mutableListOf(status),
      currentStatus = status
    ))

    resourceStateRepository.upsert(newState)

    if (event is OptOutResourceEvent) {
      log.debug("Tagging resource ${event.markedResource.uniqueId()} with \"expiration_time\":\"never\"")
      val taskId = tagResource(event.markedResource, event.workConfiguration)
      log.debug("Tagging resource ${event.markedResource.uniqueId()} in {}", StructuredArguments.kv("taskId", taskId))
    }
  }

  // todo eb: pull to another kind of ResourceTagger?
  private fun tagResource(
    resource: MarkedResource,
    workConfiguration: WorkConfiguration
  ): String {
    val taskId = with(resource.resourceType) {
      when {
        equals("serverGroup", true) -> tagAsg(resource)
        equals("image", true) -> tagImage(resource)
        else -> log.error("Failed to tag resource ${resource.uniqueId()}")
      }
    }
    taskTrackingRepository.add(
      taskId.toString(),
      TaskCompleteEventInfo(
        action = Action.OPTOUT,
        markedResources = listOf(resource),
        workConfiguration = workConfiguration,
        submittedTimeMillis = clock.instant().toEpochMilli()
      )
    )
    return taskId.toString()
  }

  private fun tagAsg(resource: MarkedResource): String {
    return taggingService.upsertAsgTag(
      UpsertServerGroupTagsRequest(
        serverGroupName = resource.resourceId,
        regions = setOf(SwabbieNamespace.namespaceParser(resource.namespace).region),
        tags = mapOf("expiration_time" to "never"),
        cloudProvider = "aws",
        cloudProviderType = "aws",
        application = applicationUtils.determineApp(resource.resource),
        description = "Setting `expiration_time` to `never` for serverGroup ${resource.uniqueId()}"
      )
    )
  }

  private fun tagImage(resource: MarkedResource): String {
    return taggingService.upsertImageTag(
      UpsertImageTagsRequest(
        imageNames = setOf(resource.name ?: resource.resourceId),
        regions = setOf(SwabbieNamespace.namespaceParser(resource.namespace).region),
        tags = mapOf("expiration_time" to "never"),
        cloudProvider = "aws",
        cloudProviderType = "aws",
        application = applicationUtils.determineApp(resource.resource),
        description = "Setting `expiration_time` to `never` for image ${resource.uniqueId()}"
      )
    )
  }
}

internal fun MarkedResource.typeAndName(): String {
  if (name == null || name == resourceId) {
    resourceId
  } else {
    "($resourceId) $name"
  }.let { suffix ->
    return resourceType
      .split("(?=[A-Z])".toRegex())
      .joinToString(" ") + ": $suffix"
  }
}

internal fun String.formatted(): String =
  this.split("(?=[A-Z])".toRegex()).joinToString(" ").toLowerCase()
