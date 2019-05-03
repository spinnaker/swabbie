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

package com.netflix.spinnaker.swabbie.test

import com.fasterxml.jackson.annotation.JsonTypeName
import com.netflix.spinnaker.swabbie.model.Grouping
import com.netflix.spinnaker.swabbie.model.GroupingType
import com.netflix.spinnaker.swabbie.model.Resource
import java.time.Instant
import java.time.temporal.ChronoUnit

@JsonTypeName("testResource")
data class TestResource(
  override val resourceId: String,
  override val resourceType: String = TEST_RESOURCE_TYPE,
  override val cloudProvider: String = TEST_RESOURCE_PROVIDER_TYPE,
  override val name: String = resourceId,
  override val createTs: Long = Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli(),
  override val grouping: Grouping? = Grouping("group", GroupingType.APPLICATION)
) : Resource() {
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}

const val TEST_RESOURCE_PROVIDER_TYPE = "testProvider"
const val TEST_RESOURCE_TYPE = "testResourceType"
