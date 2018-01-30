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

package com.netflix.spinnaker.swabbie.orca.tagging

import com.netflix.spinnaker.swabbie.ScopeOfWorkConfigurator
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP
import com.netflix.spinnaker.swabbie.tagging.ResourceTagger
import org.springframework.stereotype.Component

@Component
class EntityTagResourceTagger(
  private val taggingService: EntityTaggingService,
  private val scopeOfWorkConfigurator: ScopeOfWorkConfigurator
): ResourceTagger {
  override fun tag(markedResource: MarkedResource) {
    markedResource
      .takeIf { it.resourceType in SUPPORTED_RESOURCE_TYPES }
      ?.let {
        //TODO: fix me
        taggingService.tag(
          resourceId = it.resourceId,
          resourceType = it.resourceType,
          cloudProvider = it.cloudProvider,
          region = scopeOfWorkConfigurator.list().find { c -> c.namespace == it.configurationId }?.configuration?.location,
          tags = listOf(
            EntityTag(
              name = it.resourceId,
              value = EntityTagValue(message = "")
            )
          )
        )
      }
  }

  override fun unTag(markedResource: MarkedResource) {
    markedResource
      .takeIf { it.resourceType in SUPPORTED_RESOURCE_TYPES }
      ?.let {
        //TODO: build the entity tag and tag
      }
  }
}

private val SUPPORTED_RESOURCE_TYPES = listOf(SECURITY_GROUP)
private const val TAG_PREFIX = "spinnaker_ui_alert:swabbie_marked_for_deletion:"
