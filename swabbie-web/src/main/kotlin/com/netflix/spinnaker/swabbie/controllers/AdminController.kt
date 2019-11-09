package com.netflix.spinnaker.swabbie.controllers

import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
@RequestMapping("/admin")
class AdminController(
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val clock: Clock
) {

  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * Recalculate deletion timestamp to [retentionSeconds] seconds in the future for the
   * oldest [numResources] that are marked
   */
  @RequestMapping(value = ["/resources/recalculate/{namespace}/"], method = [RequestMethod.PUT])
  fun recalculate(
    @PathVariable namespace: String,
    @RequestParam(required = true) retentionSeconds: Long,
    @RequestParam(required = true) numResources: Int
  ) {
    log.info("Recalculating deletion timestamp for oldest $numResources resources in $namespace. " +
        "Setting to ${retentionSeconds}s from now.")

    val newTimestamp = clock.instant().plusSeconds(retentionSeconds).toEpochMilli()
    log.info("Updating deletion time to $newTimestamp for $numResources resources in ${javaClass.simpleName}.")
    val markedResources = resourceTrackingRepository.getMarkedResources()
      .filter { it.namespace == namespace }
      .sortedBy { it.resource.createTs }

    var countUpdated = 0
    markedResources.forEach { resource ->
      if (resource.projectedDeletionStamp > newTimestamp && countUpdated < numResources) {
        resource.projectedDeletionStamp = newTimestamp
        resourceTrackingRepository.upsert(resource)
        countUpdated += 1
      }
    }

    log.info("Updating deletion time to $newTimestamp complete for $countUpdated resources")
  }
}
