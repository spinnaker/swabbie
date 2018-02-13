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

package com.netflix.spinnaker.swabbie.events

import com.netflix.spinnaker.swabbie.configuration.ScopeOfWorkConfiguration
import com.netflix.spinnaker.swabbie.model.MarkedResource

const val NOTIFY  = "NOTIFY"
const val UNMARK  = "UNMARK"
const val MARK    = "MARK"
const val DELETE  = "DELETE"

abstract class Event(
  val name: String,
  open val markedResource: MarkedResource,
  open val scopeOfWorkConfiguration: ScopeOfWorkConfiguration
) {
  override fun equals(other: Any?): Boolean {
    if (other is Event) {
      return name == other.name && other.markedResource == markedResource
        && scopeOfWorkConfiguration == other.scopeOfWorkConfiguration
    }
    return super.equals(other)
  }

  override fun hashCode(): Int {
    var result = name.hashCode()
    result = 31 * result + markedResource.hashCode()
    result = 31 * result + scopeOfWorkConfiguration.hashCode()
    return result
  }
}

class OwnerNotifiedEvent(
  override val markedResource: MarkedResource,
  override val scopeOfWorkConfiguration: ScopeOfWorkConfiguration
): Event(NOTIFY, markedResource, scopeOfWorkConfiguration)

class UnMarkResourceEvent(
  override val markedResource: MarkedResource,
  override val scopeOfWorkConfiguration: ScopeOfWorkConfiguration
): Event(UNMARK, markedResource, scopeOfWorkConfiguration)

class MarkResourceEvent(
  override val markedResource: MarkedResource,
  override val scopeOfWorkConfiguration: ScopeOfWorkConfiguration
): Event(MARK, markedResource, scopeOfWorkConfiguration)

class DeleteResourceEvent(
  override val markedResource: MarkedResource,
  override val scopeOfWorkConfiguration: ScopeOfWorkConfiguration
): Event(DELETE, markedResource, scopeOfWorkConfiguration)
