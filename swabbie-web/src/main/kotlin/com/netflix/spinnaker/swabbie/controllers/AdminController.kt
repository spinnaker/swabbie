package com.netflix.spinnaker.swabbie.controllers

import com.netflix.spinnaker.swabbie.model.SwabbieNamespace
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/admin")
class AdminController(
    private val controllerUtils: ControllerUtils
) {

  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * Recalculate deletion timestamp to [retentionS] seconds in the future for the
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
    val workConfiguration = controllerUtils.findWorkConfiguration(SwabbieNamespace.namespaceParser(namespace))
    val handler = controllerUtils.findHandler(workConfiguration)
    handler.recalculateDeletionTimestamp(retentionSeconds, numResources)
  }
}