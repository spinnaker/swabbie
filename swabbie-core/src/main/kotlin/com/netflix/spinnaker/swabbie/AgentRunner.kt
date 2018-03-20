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

package com.netflix.spinnaker.swabbie

import com.netflix.spinnaker.SwabbieAgent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LockEnabledAgentRunner(
  workConfigurator: WorkConfigurator,
  private val lockManager: LockManager
) : AgentRunner {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val workConfigurations = workConfigurator.generateWorkConfigurations()

  override fun run(agent: SwabbieAgent) {
    val agentName = agent.javaClass.simpleName
    agent.initialize()
    workConfigurations.forEach { workConfiguration ->
      workConfiguration.takeIf {
        acquireLock("$agentName:${workConfiguration.namespace}")
      }?.let {
          log.info("{} processing {}", agentName, workConfiguration)
          agent.process(
            workConfiguration = workConfiguration,
            onCompleteCallback = {
              releaseLock("${agent.javaClass.simpleName}:${workConfiguration.namespace}")
              agent.finalize(workConfiguration)
            }
          )
        }
    }
  }

  private fun releaseLock(key: String) {
    log.info("releasing work for {}", key)
    lockManager.release(key)
  }

  private fun acquireLock(key: String): Boolean =
    lockManager.acquire(key, lockTtlSeconds = 3600).also {
      if (it) {
        log.info("Acquired lock for {}", key)
      } else {
        log.info("Failed to acquire lock for {}, work already in progress", key)
      }
    }
}

interface AgentRunner {
  fun run(agent: SwabbieAgent)
}
