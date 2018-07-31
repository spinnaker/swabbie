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

package com.netflix.spinnaker.swabbie.aws.model

import com.netflix.spinnaker.swabbie.model.Resource
import java.time.LocalDateTime
import java.time.ZoneId

abstract class AmazonResource(
  creationDate: String? // ISO_LOCAL_DATE_TIME format
) : Resource() {
  // falling back to 3 years prior if creationDate is nil
  override val createTs: Long = if (creationDate != null)
    LocalDateTime.parse(creationDate).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  else LocalDateTime.now().minusYears(3).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
