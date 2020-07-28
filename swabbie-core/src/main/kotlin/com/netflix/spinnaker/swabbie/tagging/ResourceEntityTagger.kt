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

package com.netflix.spinnaker.swabbie.tagging

import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import java.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ResourceEntityTagger(
  private val clock: Clock,
  private val taggingService: TaggingService
) : ResourceTagger {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun tag(markedResource: MarkedResource, workConfiguration: WorkConfiguration, description: String) {
    if (!workConfiguration.entityTaggingEnabled) {
      log.debug("Skipping tagging of resource {}", markedResource)
      return
    }

    log.debug("tagging resource {}", markedResource)
    taggingService.entityTag(
      UpsertEntityTagsRequest(
        entityRef = EntityRef(
          entityType = markedResource.resourceType.toLowerCase(),
          cloudProvider = markedResource.cloudProvider,
          entityId = markedResource.resourceId,
          region = workConfiguration.location,
          account = workConfiguration.account.name!!
        ),
        tags = listOf(
          EntityTag(
            namespace = "swabbie:${workConfiguration.namespace.toLowerCase()}",
            value = TagValue(message = tagMessage(markedResource, workConfiguration))
          )
        ),
        application = FriggaReflectiveNamer().deriveMoniker(markedResource).app ?: "swabbie",
        description = description
      )
    )
  }

  private fun tagMessage(markedResource: MarkedResource, workConfiguration: WorkConfiguration): String {
    val summaries = markedResource.summaries
      .joinToString(", ") {
        it.description
      }
    return formatMessage(summaries, markedResource, workConfiguration)
  }

  private fun formatMessage(message: String, markedResource: MarkedResource, workConfiguration: WorkConfiguration): String {
    val time = markedResource.deletionDate(clock)
    val docLink: String = documentationLink(workConfiguration)
    return "Scheduled to be cleaned up on $time<br /> \n " +
      "* $message <br /> \n" +
      "* Click <a href='" +
      "${markedResource.optOutUrl(workConfiguration)}' target='_blank'>here</a> to opt out. \n" +
      docLink
  }

  private fun documentationLink(workConfiguration: WorkConfiguration): String {
    if (workConfiguration.notificationConfiguration.docsUrl.isNotEmpty()) {
      return "* Click <a href='" +
        "${workConfiguration.notificationConfiguration.docsUrl}' target='_blank'>here</a> to read more on resource clean up"
    }
    return ""
  }

  override fun unTag(markedResource: MarkedResource, workConfiguration: WorkConfiguration, description: String) {
    if (!workConfiguration.entityTaggingEnabled) {
      log.debug("Skipping removing tag from resource {}", markedResource)
      return
    }

    log.debug("removing tagging resource {}", markedResource)
    taggingService.removeEntityTag(
      DeleteEntityTagsRequest(
        id = tagId(workConfiguration, markedResource),
        application = FriggaReflectiveNamer().deriveMoniker(markedResource).app ?: "swabbie",
        description = description
      )
    )
  }

  private fun tagId(workConfiguration: WorkConfiguration, markedResource: MarkedResource): String =
    String.format(
      "%s:%s:%s:%s:%s",
      workConfiguration.cloudProvider,
      workConfiguration.resourceType.toLowerCase(),
      markedResource.resourceId,
      workConfiguration.account.accountId,
      workConfiguration.location
    )
}
