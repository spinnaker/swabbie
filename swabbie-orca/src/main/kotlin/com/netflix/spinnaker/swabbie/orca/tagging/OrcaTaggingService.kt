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

import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import com.netflix.spinnaker.swabbie.tagging.DeleteEntityTagsRequest
import com.netflix.spinnaker.swabbie.tagging.SWABBIE_ENTITY_TAG_NAME
import com.netflix.spinnaker.swabbie.tagging.TagRequest
import com.netflix.spinnaker.swabbie.tagging.TaggingService
import com.netflix.spinnaker.swabbie.tagging.UpsertEntityTagsRequest
import com.netflix.spinnaker.swabbie.tagging.UpsertImageTagsRequest
import com.netflix.spinnaker.swabbie.tagging.UpsertServerGroupTagsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class OrcaTaggingService(
  private val orcaService: OrcaService
) : TaggingService {
  val log: Logger = LoggerFactory.getLogger(javaClass)

  override fun removeEntityTag(tagRequest: TagRequest) {
    if (tagRequest is DeleteEntityTagsRequest) {
      AuthenticatedRequest.allowAnonymous {
        orcaService.orchestrate(
          OrchestrationRequest(
            application = tagRequest.application,
            description = tagRequest.description,
            job = listOf(
              OrcaJob(
                type = tagRequest.type,
                context = mutableMapOf(
                  "tags" to listOf(SWABBIE_ENTITY_TAG_NAME),
                  "id" to tagRequest.id
                )
              )
            )
          )
        )
      }
    }
  }

  override fun entityTag(tagRequest: TagRequest) {
    if (tagRequest is UpsertEntityTagsRequest) {
      AuthenticatedRequest.allowAnonymous {
        orcaService.orchestrate(
          OrchestrationRequest(
            application = tagRequest.application,
            description = tagRequest.description,
            job = listOf(
              OrcaJob(
                type = tagRequest.type,
                context = mutableMapOf(
                  "tags" to tagRequest.tags,
                  "entityRef" to tagRequest.entityRef
                )
              )
            )
          )
        )
      }
    }
  }

  override fun upsertImageTag(tagRequest: UpsertImageTagsRequest): String {
    return AuthenticatedRequest.allowAnonymous {
      orcaService.orchestrate(
        OrchestrationRequest(
          application = tagRequest.application,
          description = tagRequest.description,
          job = listOf(
            OrcaJob(
              type = tagRequest.type,
              context = mutableMapOf(
                "imageNames" to tagRequest.imageNames,
                "regions" to tagRequest.regions,
                "tags" to tagRequest.tags,
                "cloudProvider" to tagRequest.cloudProvider,
                "cloudProviderType" to tagRequest.cloudProviderType
              )
            )
          )
        )
      ).taskId()
    }
  }

  override fun upsertAsgTag(tagRequest: UpsertServerGroupTagsRequest): String {
    return AuthenticatedRequest.allowAnonymous {
      orcaService.orchestrate(
        OrchestrationRequest(
          application = tagRequest.application,
          description = tagRequest.description,
          job = listOf(
            OrcaJob(
              type = tagRequest.type,
              context = mutableMapOf(
                "serverGroupName" to tagRequest.serverGroupName,
                "regions" to tagRequest.regions,
                "tags" to tagRequest.tags,
                "cloudProvider" to tagRequest.cloudProvider,
                "cloudProviderType" to tagRequest.cloudProviderType
              )
            )
          )
        )
      ).taskId()
    }
  }
}
