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

package com.netflix.spinnaker.swabbie.aws

import com.netflix.spinnaker.swabbie.ResourceOwnerResolutionStrategy
import com.netflix.spinnaker.swabbie.model.Resource
import org.springframework.stereotype.Component

@Component
class AmazonTagOwnerResolutionStrategy : ResourceOwnerResolutionStrategy<Resource> {

  override fun primaryFor(): Set<String> = emptySet()

  override fun resolve(resource: Resource): String? {
    if ("tags" in resource.details) {
      (resource.details["tags"] as? List<Map<*, *>>)?.let { tags ->
        return getOwner(tags)
      }
    }

    return null
  }

  private fun getOwner(tags: List<Map<*, *>>): String? {
    return tags.find { it["key"] == "creator" || it["key"] == "owner" }?.get("value") as? String
      ?: return tags.find { "owner" in it || "creator" in it }?.map { it.value }?.first() as? String
  }
}
