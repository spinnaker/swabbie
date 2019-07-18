package com.netflix.spinnaker.swabbie.aws.edda

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.EddaApiClient
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.edda.providers.EddaApiSupport
import com.netflix.spinnaker.swabbie.aws.edda.providers.maxRetries
import com.netflix.spinnaker.swabbie.aws.edda.providers.retryBackOffMillis
import com.netflix.spinnaker.swabbie.aws.instances.AmazonInstance
import com.netflix.spinnaker.swabbie.aws.launchconfigurations.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.model.INSTANCE
import com.netflix.spinnaker.swabbie.model.LAUNCH_CONFIGURATION
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import retrofit.RetrofitError

class Edda(
  eddaApiClients: List<EddaApiClient>,
  private val retrySupport: RetrySupport,
  private val registry: Registry
) : AWS, EddaApiSupport(eddaApiClients, registry) {
  override fun getLaunchConfigurations(params: Parameters): List<AmazonLaunchConfiguration> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getLaunchConfigurations()
    }

    return emptyList()
  }

  override fun getLaunchConfiguration(params: Parameters): AmazonLaunchConfiguration? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getLaunchConfiguration(params.id)
    }

    return null
  }

  private fun EddaService.getLaunchConfiguration(launchConfigurationName: String): AmazonLaunchConfiguration? {
    return try {
      retrySupport.retry({
        try {
          AuthenticatedRequest.allowAnonymous { this.getLaunchConfig(launchConfigurationName) }
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", LAUNCH_CONFIGURATION)).increment()
      log.error("failed to get launch config {}", launchConfigurationName, e)
      throw e
    }
  }

  private fun EddaService.getLaunchConfigurations(): List<AmazonLaunchConfiguration> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getLaunchConfigs() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", LAUNCH_CONFIGURATION)).increment()
      log.error("failed to get instances", e)
      throw e
    }
  }

  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun getInstances(params: Parameters): List<AmazonInstance> {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getNonTerminatedInstances()
    }

    return emptyList()
  }

  override fun getInstance(params: Parameters): AmazonInstance? {
    withEddaClient(
      region = params.region,
      accountId = params.account,
      environment = params.environment
    )?.run {
      return getSingleInstance(params.id)
    }

    return null
  }

  private fun EddaService.getSingleInstance(instanceId: String): AmazonInstance? {
    return try {
      retrySupport.retry({
        try {
          AuthenticatedRequest.allowAnonymous { this.getInstance(instanceId) }
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(
        eddaFailureCountId.withTags("resourceType", INSTANCE)).increment()
      log.error("failed to get instance {}", instanceId, e)
      throw e
    }
  }

  private fun EddaService.getNonTerminatedInstances(): List<AmazonInstance> {
    return try {
      retrySupport.retry({
        AuthenticatedRequest.allowAnonymous { this.getInstances() }
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", INSTANCE)).increment()
      log.error("failed to get instances", e)
      throw e
    }
  }
}
