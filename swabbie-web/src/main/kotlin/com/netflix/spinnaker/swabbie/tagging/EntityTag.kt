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

import com.netflix.spinnaker.swabbie.Tag
import com.netflix.spinnaker.swabbie.TagRequest

data class EntityTag(
  val namespace: String,
  val value: Value?,
  val valueType: String = "object",
  val name: String = "spinnaker_ui_alert:swabbie_deletion_candidate"
): Tag

data class Value(
  val message: String,
  val type: String = "alert"
)

data class EntityRef(
  val entityType: String,
  val cloudProvider: String,
  val entityId: String,
  val region: String,
  val account: String
)

data class UpsertEntityTagsRequest(
  val entityRef: EntityRef?,
  val tags: List<EntityTag>?,
  override val application: String,
  override val description: String = "Resource marked as cleanup candidate"
): EntityTagRequest("upsertEntityTags", application, description)

data class DeleteEntityTagsRequest(
  val id: String,
  override val application: String,
  override val description: String = "Removing swabbie tag"
): EntityTagRequest("deleteEntityTags", application, description)

open class EntityTagRequest(
  open val type: String,
  open val application: String,
  open val description: String,
  val category: String = "Swabbie"
): TagRequest
