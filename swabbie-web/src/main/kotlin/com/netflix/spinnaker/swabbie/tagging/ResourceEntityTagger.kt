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
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Clock

@Component
@ConditionalOnExpression("\${swabbie.taggingEnabled}")
class ResourceEntityTagger(
  private val taggingService: EntityTaggingService,
  private val applicationCache: InMemoryCache<Application>,
  private val clock: Clock
): ResourceTagger {
  @Value("\${swabbie.optOut.url}")
  lateinit var optOutUrl: String
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
            account = workConfiguration.account.name
          ),
          tags = listOf(
            EntityTag(
              namespace = "swabbie:${workConfiguration.namespace.toLowerCase()}",
              value = TagValue(message = NotificationMessage.body(MessageType.TAG, clock, optOutUrl, markedResource))
            )
          ),
          application = FriggaReflectiveNamer().deriveMoniker(markedResource).app,
          description = description
        ).let {
          taggingService.tag(it)
        }
      }
  }

  private fun supportedForResource(markedResource: MarkedResource) =
    markedResource.resourceType in SUPPORTED_RESOURCE_TYPES && applicationCache.contains(FriggaReflectiveNamer().deriveMoniker(markedResource)?.app)

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

  private fun tagId(workConfiguration: WorkConfiguration, markedResource: MarkedResource) =
    "${workConfiguration.cloudProvider}:${workConfiguration.resourceType.toLowerCase()}:${markedResource.resourceId}:${workConfiguration.account.accountId}:${workConfiguration.location}"
}

private val SUPPORTED_RESOURCE_TYPES = listOf(SECURITY_GROUP)
