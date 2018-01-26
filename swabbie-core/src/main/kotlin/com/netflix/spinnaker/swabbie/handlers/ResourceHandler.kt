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

package com.netflix.spinnaker.swabbie.handlers

import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription

interface ResourceHandler {
  fun handles(resourceType: String, cloudProvider: String): Boolean
  fun fetchResources(markResourceDescription: MarkResourceDescription): List<Resource>?
  fun fetchResource(markedResource: MarkedResource): Resource?
  fun getNameSpace(): String

  fun mark(markResourceDescription: MarkResourceDescription)
  fun cleanup(markedResource: MarkedResource)
}
