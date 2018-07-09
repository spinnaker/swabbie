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

import com.netflix.spinnaker.swabbie.Cacheable
import com.netflix.spinnaker.swabbie.exclusions.Excludable

interface Account : Excludable {
  val accountId: String?
  val eddaEnabled: Boolean
  val edda: String?
  val type: String
  val regions: List<Region>?
}

data class Region(
  val deprecated: Boolean = false,
  val name: String
)

/**
 * An account managed by Spinnaker
 */
data class SpinnakerAccount(
  override val eddaEnabled: Boolean,
  override val accountId: String?,
  override val type: String,
  override val name: String,
  override val edda: String?,
  override val regions: List<Region>?
) : Account, Cacheable, HasDetails() {
  override val resourceId: String
    get() = accountId!!
  override val resourceType: String
    get() = "account"
  override val cloudProvider: String
    get() = type
}

data class EmptyAccount(
  override val accountId: String? = "",
  override val type: String = "",
  override val name: String = "",
  override val eddaEnabled: Boolean = false,
  override val edda: String? = "",
  override val regions: List<Region> = emptyList()
) : Account, HasDetails() {
  override val resourceId: String
    get() = accountId!!
  override val resourceType: String
    get() = type
  override val cloudProvider: String
    get() = type
}
