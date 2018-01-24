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

package com.netflix.spinnaker.swabbie.scheduler

import com.netflix.spinnaker.swabbie.model.SECURITY_GROUP
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WorkProducer
constructor(
  private val workRepository: WorkRepository
) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  fun createWork(): List<MarkResourceDescription> {
    val work = getAllWork()
    log.info("work {}", work)
    work.forEach { mark ->
      workRepository.createWork(mark)
    }

    return work
  }


  private fun getAllWork(): List<MarkResourceDescription> {
    // TODO: create these and store in redis. Move logic to a scheduler class of some kind
    return listOf(MarkResourceDescription("aws:test:us-east-1", SECURITY_GROUP, "aws", RetentionPolicy(emptyList(), 10)))
  }

  fun remove(w: MarkResourceDescription) {
    workRepository.remove(w)
  }
}
