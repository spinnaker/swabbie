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

package com.netflix.spinnaker.swabbie.model

import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.swabbie.Cacheable
import com.netflix.spinnaker.swabbie.exclusions.Excludable
import com.netflix.spinnaker.swabbie.exclusions.ExclusionPolicy

interface Account : Excludable {
  val accountId: String?
  val type: String
}

/**
 * An account managed by Spinnaker
 */
data class SpinnakerAccount(
  override val accountId: String?,
  override val type: String,
  override val name: String
) : Account, Cacheable {
  override fun shouldBeExcluded(exclusionPolicies: List<ExclusionPolicy>, exclusions: List<Exclusion>): Boolean {
    return exclusionPolicies.find { it.apply(this, exclusions) } != null
  }
}

data class EmptyAccount(
  override val accountId: String? = "",
  override val type: String = "",
  override val name: String = ""
) : Account {
  override fun shouldBeExcluded(exclusionPolicies: List<ExclusionPolicy>, exclusions: List<Exclusion>): Boolean = false
}
