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

import com.netflix.spinnaker.swabbie.orca.service.OrcaService
import com.netflix.spinnaker.swabbie.tagging.Tag
import com.netflix.spinnaker.swabbie.tagging.TaggingService

class EntityTaggingService(
  private val orcaService: OrcaService
): TaggingService {
  override fun tag(resourceId: String, resourceType: String, cloudProvider: String, region: String?, tags: List<Tag>) {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

}
