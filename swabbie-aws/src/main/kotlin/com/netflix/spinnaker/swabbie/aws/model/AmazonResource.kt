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

import com.netflix.frigga.ami.AppVersion
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.Dates
import com.netflix.spinnaker.swabbie.model.Grouping
import com.netflix.spinnaker.swabbie.model.GroupingType
import com.netflix.spinnaker.swabbie.model.Resource
import java.time.LocalDateTime
import java.time.ZoneId

abstract class AmazonResource(
  creationDate: String? // ISO_LOCAL_DATE_TIME format
) : Resource() {

  override val grouping: Grouping?
    get() {
      if (resourceType.contains("image", ignoreCase = true) || resourceType.contains("snapshot", ignoreCase = true)) {
        // Images and snapshots have only packageName, not app, to group by
        getTagValue("appversion")?.let { AppVersion.parseName(it as String)?.packageName }?.let { packageName ->
          return Grouping(packageName, GroupingType.PACKAGE_NAME)
        }
        return null
      } else {
        FriggaReflectiveNamer().deriveMoniker(this).app?.let { app ->
          return Grouping(app, GroupingType.APPLICATION)
        }
        return null
      }
    }

  override val createTs: Long =
    if (!creationDate.isNullOrBlank())
      Dates.toLocalDateTime(creationDate!!)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    else
      LocalDateTime.now()
        .minusYears(3) // falling back to 3 years prior if creationDate is nil to support legacy resources
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
