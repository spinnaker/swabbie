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

package com.netflix.spinnaker.swabbie.repositories

import com.netflix.spinnaker.swabbie.model.ResourceState

interface ResourceStateRepository {
  fun get(resourceId: String, namespace: String): ResourceState?
  fun upsert(resourceState: ResourceState)
  fun getAll(): List<ResourceState>
  fun getByStatus(status: String): List<ResourceState>
  //todo eb: need to get things that failed which have a message instead of name. maybe rethink that?
}
