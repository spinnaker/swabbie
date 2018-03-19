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

package com.netflix.spinnaker.swabbie.work

import com.netflix.spinnaker.SwabbieAgent
import com.netflix.spinnaker.swabbie.LockManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WorkProcessor(
  workConfigurator: WorkConfigurator,
  private val lockManager: LockManager
) : Processor {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val workConfigurations = workConfigurator.generateWorkConfigurations()

  override fun process(agent: SwabbieAgent, fn: (workConfiguration: WorkConfiguration?) -> Unit) {
    val agentName = agent.javaClass.simpleName.toLowerCase()
    log.info("{} processing {}", agentName, workConfigurations)
    workConfigurations.forEach {
      it.takeIf {
        lockManager.acquire("$agentName:${it.namespace}", lockTtlSeconds = 3600)
      }?.let {
          try {
            fn(it)
          } finally {
            lockManager.release("$agentName:${it.namespace}")
          }
        }
    }
  }
}

interface Processor {
  fun process(agent: SwabbieAgent, fn: (workConfiguration: WorkConfiguration?) -> Unit)
}
