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

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LockEnabledAgentRunner(
  workConfigurator: WorkConfigurator,
  private val lockManager: LockManager,
  private val registry: Registry
) : AgentRunner {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private val workConfigurations = workConfigurator.generateWorkConfigurations()

  override fun run(agent: SwabbieAgent) {
    val agentName = agent.javaClass.simpleName
    agent.initialize()
    workConfigurations.forEach { workConfiguration ->
      workConfiguration.takeIf {
        lock(agent, workConfiguration, acquire = true)
      }?.let {
          log.info("{} running with configuration {}", agentName, workConfiguration)
          agent.process(
            workConfiguration = workConfiguration,
            onCompleteCallback = {
              lock(agent, workConfiguration, acquire = false)
              agent.finalize(workConfiguration)
            }
          )
        }
    }
  }

  private val lockId: Id = registry.createId("swabbie.locks")
  private fun lock(agent: SwabbieAgent, workConfiguration: WorkConfiguration, acquire: Boolean): Boolean =
    "${agent.javaClass.simpleName}:${workConfiguration.namespace}".let { locksName ->
      if (acquire) {
        return lockManager.acquire(locksName, lockTtlSeconds = 3600).also { success ->
          recordLockAcquired(agent.javaClass.simpleName, workConfiguration, success)
          if (success) {
            log.info("Acquired lock for {}", locksName)
          } else {
            log.info("Failed to acquire lock for {}, work already in progress", locksName)
          }
        }
      } else {
        return lockManager.release(locksName).also { success ->
          if (success) {
            log.info("Released lock for {}", locksName)
          } else {
            log.info("Failed to release lock for {}", locksName)
          }
        }
      }
    }

  private fun recordLockAcquired(agentName: String, workConfiguration: WorkConfiguration, success: Boolean) {
    registry.counter(
      lockId.withTags(
        "result", if (success) "success" else "failure",
        "agentName", agentName,
        "configuration", workConfiguration.namespace,
        "resourceType", workConfiguration.resourceType
      )).increment()
  }

}

interface AgentRunner {
  fun run(agent: SwabbieAgent)
}
