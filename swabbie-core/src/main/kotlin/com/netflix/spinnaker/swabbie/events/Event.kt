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

import com.netflix.spinnaker.swabbie.model.MarkedResource


const val NOTIFY = "NOTIFY"
const val UNMARK = "UNMARK"
const val MARK = "MARK"
const val DELETE = "DELETE"

abstract class Event(
  open val markedResource: MarkedResource,
  val name: String
)

class NotifyOwnerEvent(
  override val markedResource: MarkedResource
): Event(markedResource, NOTIFY)

class UnMarkResourceEvent(
  override val markedResource: MarkedResource
): Event(markedResource, UNMARK)

class MarkResourceEvent(
  override val markedResource: MarkedResource
): Event(markedResource, MARK)

class DeleteResourceEvent(
  override val markedResource: MarkedResource
): Event(markedResource, DELETE)
