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

package com.netflix.spinnaker.swabbie.tagging.entitytags

import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.configuration.ScopeOfWorkConfiguration
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP
import com.netflix.spinnaker.swabbie.tagMessage
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Clock

@Component
@ConditionalOnExpression("\${swabbie.taggingEnabled}")
class EntityTagResourceTagger(
  private val taggingService: EntityTaggingService,
  private val clock: Clock
): ResourceTagger {
  override fun tag(markedResource: MarkedResource, scopeOfWorkConfiguration: ScopeOfWorkConfiguration) {
    markedResource
      .takeIf { it.resourceType in SUPPORTED_RESOURCE_TYPES }
      ?.let {
        UpsertEntityTagsRequest(
          entityRef = EntityRef(
            entityType = markedResource.resourceType.toLowerCase(),
            cloudProvider = markedResource.cloudProvider,
            entityId = markedResource.resourceId,
            region = scopeOfWorkConfiguration.location,
            account = scopeOfWorkConfiguration.account.name
          ),
          tags = listOf(
            EntityTag(
              namespace = "swabbie:${scopeOfWorkConfiguration.namespace.toLowerCase()}",
              value = Value(message = tagMessage(it, clock))
            )
          ),
          application = FriggaReflectiveNamer().deriveMoniker(markedResource).app
        ).let {
          taggingService.tag(it)
        }
      }
  }

  override fun unTag(markedResource: MarkedResource, scopeOfWorkConfiguration: ScopeOfWorkConfiguration) {
    markedResource
      .takeIf { it.resourceType in SUPPORTED_RESOURCE_TYPES }
      ?.let {
        taggingService.removeTag(
          DeleteEntityTagsRequest(
            id = "${scopeOfWorkConfiguration.cloudProvider}:" +
              "${scopeOfWorkConfiguration.resourceType.toLowerCase()}: " +
              "${it.resourceId}:" +
              "${scopeOfWorkConfiguration.account.accountId}:" +
              scopeOfWorkConfiguration.location,
            application = FriggaReflectiveNamer().deriveMoniker(markedResource).app
          )
        )
      }
  }
}

private val SUPPORTED_RESOURCE_TYPES = listOf(SECURITY_GROUP)
