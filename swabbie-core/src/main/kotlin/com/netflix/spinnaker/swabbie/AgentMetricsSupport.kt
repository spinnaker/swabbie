package com.netflix.spinnaker.swabbie

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.LongTaskTimer
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

open class AgentMetricsSupport(
  private val registry: Registry
) {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  protected val resourcesExcludedCounter = AtomicInteger(0)
  protected val markDurationTimer: LongTaskTimer = registry.longTaskTimer("swabbie.resources.mark.duration")
  protected val resourcesVisitedId: Id = registry.createId("swabbie.resources.visited")
  protected val noxtificationsId: Id = registry.createId("swabbie.resources.notifications")

  private val markViolationsId: Id = registry.createId("swabbie.resources.markViolations")
  private val resourcesExcludedId: Id = registry.createId("swabbie.resources.excluded")
  private val resourceFailureId: Id = registry.createId("swabbie.resources.failures")
  private val candidatesCountId: Id = registry.createId("swabbie.resources.candidatesCount")
  protected fun recordMarkMetrics(markerTimerId: Long,
                                  workConfiguration: WorkConfiguration,
                                  violationCounter: AtomicInteger,
                                  candidateCounter: AtomicInteger) {
    log.info("Found {} clean up candidates with configuration {}", candidateCounter.get(), workConfiguration)
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
    log.error("Failed while invoking $javaClass", e)
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
