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

import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.work.WorkConfiguration

interface ResourceHandler<out T : Resource> {
  fun handles(workConfiguration: WorkConfiguration): Boolean
  fun getUpstreamResources(workConfiguration: WorkConfiguration): List<T>?
  fun getUpstreamResource(markedResource: MarkedResource, workConfiguration: WorkConfiguration): T?

  fun clean(markedResource: MarkedResource, workConfiguration: WorkConfiguration, postClean: () -> Unit)
  fun mark(workConfiguration: WorkConfiguration, postMark: () -> Unit)

  fun postProcessing(configuration: WorkConfiguration, action: Action)
}
