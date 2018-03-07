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

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.WorkConfiguration

interface ResourceHandler {
  fun handles(resourceType: String, cloudProvider: String): Boolean
  fun getUpstreamResources(workConfiguration: WorkConfiguration): List<Resource>?
  fun getUpstreamResource(markedResource: MarkedResource, workConfiguration: WorkConfiguration): Resource?

  fun clean(markedResource: MarkedResource, workConfiguration: WorkConfiguration, postClean: () -> Unit)
  fun mark(workConfiguration: WorkConfiguration, postMark: () -> Unit)
}
