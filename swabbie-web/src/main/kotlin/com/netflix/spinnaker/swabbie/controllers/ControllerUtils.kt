package com.netflix.spinnaker.swabbie.controllers

import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.model.SwabbieNamespace
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ControllerUtils(
  private val resourceTypeHandlers: List<ResourceTypeHandler<*>>,
  private val workConfigurations: List<WorkConfiguration>
) {

  private val log = LoggerFactory.getLogger(javaClass)

  init {
    log.info("Available resource type handlers are: $resourceTypeHandlers")
  }

  fun findWorkConfiguration(namespace: SwabbieNamespace): WorkConfiguration {
    return workConfigurations.find { workConfiguration ->
      workConfiguration.account.name == namespace.accountName &&
        workConfiguration.cloudProvider == namespace.cloudProvider &&
        workConfiguration.resourceType.equals(namespace.resourceType, true) &&
        workConfiguration.location == namespace.region
    } ?: throw NotFoundException("No configuration found for $namespace")
  }

  fun findHandler(workConfiguration: WorkConfiguration): ResourceTypeHandler<*> {
    return resourceTypeHandlers.find { handler ->
      handler.handles(workConfiguration)
    } ?: throw NotFoundException("No handlers for ${workConfiguration.namespace}")
  }
}
