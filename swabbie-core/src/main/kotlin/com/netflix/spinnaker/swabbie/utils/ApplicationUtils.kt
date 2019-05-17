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

package com.netflix.spinnaker.swabbie.utils

import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.Application
import com.netflix.spinnaker.swabbie.model.Grouping
import com.netflix.spinnaker.swabbie.model.GroupingType
import com.netflix.spinnaker.swabbie.model.Resource
import org.springframework.stereotype.Component

@Component
open class ApplicationUtils(
  private val applicationsCaches: List<InMemoryCache<Application>>
) {

  /**
   * If the resource has an app grouping and that app exists in the cache of applicaitons we know about,
   *  return that app.
   * Otherwise, return "swabbie".
   */
  fun determineApp(resource: Resource): String {
    val grouping: Grouping = resource.grouping ?: return "swabbie"
    if (grouping.type == GroupingType.APPLICATION) {
      if (applicationsCaches.any { it.contains(grouping.value) }) {
        return grouping.value
      }
    }
    return "swabbie"
  }
}
