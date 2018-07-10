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

import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.ResourceTagger
import com.netflix.spinnaker.swabbie.model.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Clock

@Component
@ConditionalOnExpression("\${swabbie.taggingEnabled}")
class ResourceEntityTagger(
  private val clock: Clock,
  private val swabbieProperties: SwabbieProperties,
  private val taggingService: EntityTaggingService
) : ResourceTagger {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun tag(markedResource: MarkedResource, workConfiguration: WorkConfiguration, description: String) {
    markedResource
      .takeIf { supportedForResource(it) }
      ?.let {
        log.info("tagging resource {}", it)
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
              value = TagValue(message = tagMessage(markedResource))
            )
          ),
          application = FriggaReflectiveNamer().deriveMoniker(markedResource).app,
          description = description
        ).let {
          taggingService.tag(it)
        }
      }
  }

  private fun tagMessage(markedResource: MarkedResource): String =
    markedResource.summaries.joinToString(", ") {
      it.description
    }.let { summary ->
      val time = markedResource.humanReadableDeletionTime(clock)
      "Scheduled to be cleaned up on $time<br /> \n " +
        "* $summary <br /> \n" +
        "* Click <a href='${swabbieProperties.optOutBaseUrl}' target='_blank'>here</a> to opt out."
    }

  private fun supportedForResource(markedResource: MarkedResource): Boolean =
    markedResource.resourceType in SUPPORTED_RESOURCE_TYPES
      && FriggaReflectiveNamer().deriveMoniker(markedResource)?.app != null


  override fun unTag(markedResource: MarkedResource, workConfiguration: WorkConfiguration, description: String) {
    markedResource
      .takeIf { supportedForResource(it) }
      ?.let {
        log.info("removing tagging resource {}", it)
        taggingService.removeTag(
          DeleteEntityTagsRequest(
            id = tagId(workConfiguration, it),
            application = FriggaReflectiveNamer().deriveMoniker(markedResource).app,
            description = description
          )
        )
      }
  }

  private fun tagId(workConfiguration: WorkConfiguration, markedResource: MarkedResource): String =
    String.format("%s:%s:%s:%s:%s",
      workConfiguration.cloudProvider,
      workConfiguration.resourceType.toLowerCase(),
      markedResource.resourceId,
      workConfiguration.account.accountId,
      workConfiguration.location
    )
}

private val SUPPORTED_RESOURCE_TYPES = listOf(SECURITY_GROUP, LOAD_BALANCER)
