/*
 *
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.repository

/**
 * Track a resource that is in use by another type of resource.
 * This repository provides a way to collect information about use from one resource and query that while processing
 *  another resource. For example, AWS provides some one way mappings about use (image -> snapshot). This repository
 *  is used to record if a snapshot is in use by an image.
 */
interface UsedResourceRepository {
  fun recordUse(resourceType: String, id: String, namespace: String)
  fun isUsed(resourceType: String, id: String, namespace: String): Boolean
}
