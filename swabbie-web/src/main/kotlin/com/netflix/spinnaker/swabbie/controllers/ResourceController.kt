/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.controllers


import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.ResourceStateRepository
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/resources")
class ResourceController(
  private val resourceStateRepository: ResourceStateRepository
) {

  @RequestMapping(value = "/states", method = arrayOf(RequestMethod.GET))
  fun resourceStates(): List<ResourceState>? = resourceStateRepository.getAll()

  @RequestMapping(value="/state", method = arrayOf(RequestMethod.GET))
  fun resourceState(
    @RequestParam provider: String,
    @RequestParam account: String,
    @RequestParam location: String,
    @RequestParam resourceId: String,
    @RequestParam resourceType: String
  ): ResourceState? =
    "$provider:$account:$location:$resourceType".toLowerCase()
      .let {
        return resourceStateRepository.get(resourceId, it)
      }
}




