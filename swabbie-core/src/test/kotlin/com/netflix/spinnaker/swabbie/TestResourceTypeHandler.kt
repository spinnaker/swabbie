/*
 *
 *  * Copyright 2018 Netflix, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License")
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.ResourceTypeConfiguration.RuleDefinition
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.ResourceEvaluation
import com.netflix.spinnaker.swabbie.model.Result
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskCompleteEventInfo
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.rules.RulesEngine
import com.netflix.spinnaker.swabbie.test.TestResource
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher

/**
 * A test resource type handler that provides fake resources to test abstract resource type handler logic
 */
class TestResourceTypeHandler(
  clock: Clock,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  ownerResolver: OwnerResolver<TestResource>,
  applicationEventPublisher: ApplicationEventPublisher,
  private val exclusionPolicies: MutableList<ResourceExclusionPolicy> = mutableListOf(),
  notifier: Notifier,
  private val rulesEngine: RulesEngine,
  private var simulatedCandidates: MutableList<TestResource> = mutableListOf(),
  registry: Registry = NoopRegistry(),
  private val taskTrackingRepository: TaskTrackingRepository,
  resourceUseTrackingRepository: ResourceUseTrackingRepository,
  dynamicConfigService: DynamicConfigService,
  notificationQueue: NotificationQueue
) : AbstractResourceTypeHandler<TestResource>(
  registry,
  clock,
  rulesEngine,
  resourceTrackingRepository,
  resourceStateRepository,
  exclusionPolicies,
  ownerResolver,
  notifier,
  applicationEventPublisher,
  resourceUseTrackingRepository,
  SwabbieProperties(),
  dynamicConfigService,
  notificationQueue
) {
  fun setCandidates(candidates: MutableList<TestResource>) {
    simulatedCandidates = candidates
  }

  fun clearCandidates() {
    simulatedCandidates = mutableListOf()
  }

  override fun deleteResources(
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ) {
    markedResources.forEach { m ->
      val found = simulatedCandidates.contains(m.resource)
      if (found) {
        simulatedCandidates.remove(m.resource)
        taskTrackingRepository.add(
          "deleteTaskId",
          TaskCompleteEventInfo(Action.DELETE, listOf(m), workConfiguration, null)
        )
      }
    }
  }

  // simulates querying for a resource upstream
  override fun getCandidate(
    resourceId: String,
    resourceName: String,
    workConfiguration: WorkConfiguration
  ): TestResource? {
    return simulatedCandidates.find { resourceId == it.resourceId }
  }

  override fun preProcessCandidates(
    candidates: List<TestResource>,
    workConfiguration: WorkConfiguration
  ): List<TestResource> {
    log.debug("pre-processing test resources {}", candidates)
    return candidates
  }

  override fun handles(workConfiguration: WorkConfiguration): Boolean {
    return rulesEngine.getRules(workConfiguration).isNotEmpty()
  }

  override fun getCandidates(workConfiguration: WorkConfiguration): List<TestResource>? {
    return simulatedCandidates
  }

  override fun evaluateCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): ResourceEvaluation {
    return ResourceEvaluation(
      namespace = workConfiguration.namespace,
      resourceId = resourceId,
      wouldMark = false,
      wouldMarkReason = "This is a test",
      summaries = listOf()
    )
  }
}

class TestRule(
  private val invalidOn: (Resource, Map<String, Any?>) -> Boolean,
  private val summary: Summary?,
  private val name: String = ""
) : Rule {
  override fun <T : Resource> applicableForType(clazz: Class<T>): Boolean {
    return TestResource::class.java.isAssignableFrom(clazz)
  }

  override fun <T : Resource> apply(resource: T, ruleDefinition: RuleDefinition?): Result {
    return if (invalidOn(resource, ruleDefinition?.parameters ?: emptyMap())) Result(summary) else Result(null)
  }

  override fun name(): String {
    return name
  }
}
