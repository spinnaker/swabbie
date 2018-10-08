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

package com.netflix.spinnaker.swabbie.aws.images

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.aws.model.AmazonResource
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.ResourceType

@JsonTypeName("amazonImage")
data class AmazonImage(
  val imageId: String,
  val ownerId: String?,
  val description: String?,
  val state: String,
  override val resourceId: String = imageId,
  override val resourceType: String = ResourceType.IMAGE.toString(),
  override val cloudProvider: String = AWS,
  override val name: String?,
  private val creationDate: String?
) : AmazonResource(creationDate)
