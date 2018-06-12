package com.netflix.spinnaker.swabbie

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.LongTaskTimer
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import java.util.concurrent.atomic.AtomicInteger

open class MetricsSupport(
  private val registry: Registry
) {
  protected val resourcesExcludedCounter = AtomicInteger(0)
  protected val markDurationTimer: LongTaskTimer = registry.longTaskTimer("swabbie.resources.mark.duration")
  protected val resourcesVisitedId: Id = registry.createId("swabbie.resources.visited")
  protected val noxtificationsId: Id = registry.createId("swabbie.resources.notifications")

  private val markViolationsId: Id = registry.createId("swabbie.resources.markViolations")
  private val resourcesExcludedId: Id = registry.createId("swabbie.resources.excluded")
  private val resourceFailureId: Id = registry.createId("swabbie.resources.failures")
  private val candidatesCountId: Id = registry.createId("swabbie.resources.candidatesCount")

  protected val markCountId: Id = registry.createId("swabbie.resources.markCount")
  protected val unMarkCountId: Id = registry.createId("swabbie.resources.unMarkCount")
  protected val deleteCountId: Id = registry.createId("swabbie.resources.deleteCount")
  protected val notifyCountId: Id = registry.createId("swabbie.resources.notifyCount")

  protected val failedAgentId: Id = registry.createId("swabbie.agents.failed")
  protected val lastRunAgeId: Id = registry.createId("swabbie.agents.run.age")

  protected fun recordMarkMetrics(markerTimerId: Long,
                                  workConfiguration: WorkConfiguration,
                                  violationCounter: AtomicInteger,
                                  candidateCounter: AtomicInteger) {
    markDurationTimer.stop(markerTimerId)
    registry.gauge(
      candidatesCountId.withTags(
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName
      )).set(candidateCounter.toDouble())

    registry.gauge(
      markViolationsId.withTags(
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName
      )).set(violationCounter.toDouble())

    registry.gauge(
      resourcesExcludedId.withTags(
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName
      )).set(resourcesExcludedCounter.toDouble())
  }

  protected fun recordFailureForAction(action: Action, workConfiguration: WorkConfiguration, e: Exception) {
    registry.counter(
      resourceFailureId.withTags(
        "action", action.name,
        "resourceType", workConfiguration.resourceType,
        "configuration", workConfiguration.namespace,
        "resourceTypeHandler", javaClass.simpleName,
        "exception", e.javaClass.simpleName
      )).increment()
  }
}
