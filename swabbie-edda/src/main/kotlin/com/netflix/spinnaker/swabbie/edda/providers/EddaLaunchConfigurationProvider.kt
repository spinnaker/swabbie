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

package com.netflix.spinnaker.swabbie.edda.providers

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.EddaApiClient
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.swabbie.Parameters
import com.netflix.spinnaker.swabbie.ResourceProvider
import com.netflix.spinnaker.swabbie.aws.launchconfigs.AmazonLaunchConfiguration
import com.netflix.spinnaker.swabbie.edda.EddaService
import com.netflix.spinnaker.swabbie.model.LAUNCH_CONFIGURATION
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import retrofit.RetrofitError

@Component
open class EddaLaunchConfigurationProvider(
  eddaApiClients: List<EddaApiClient>,
  private val retrySupport: RetrySupport,
  private val registry: Registry
) : ResourceProvider<AmazonLaunchConfiguration>, EddaApiSupport(eddaApiClients, registry) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun getAll(params: Parameters): List<AmazonLaunchConfiguration>? =
    withEddaClient(region = params["region"] as String, accountId = params["account"] as String).run {
      return getLaunchConfigurations()
    }

  override fun getOne(params: Parameters): AmazonLaunchConfiguration? {
    withEddaClient(region = params["region"] as String, accountId = params["account"] as String).run {
      return getLaunchConfiguration(params["launchConfigurationName"] as String)
    }
  }

  private fun EddaService.getLaunchConfigurations(): List<AmazonLaunchConfiguration> {
    return try {
      retrySupport.retry({
        this.getLaunchConfigs()
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", LAUNCH_CONFIGURATION)).increment()
      log.error("failed to get instances", e)
      throw e
    }
  }

  private fun EddaService.getLaunchConfiguration(launchConfigurationName: String): AmazonLaunchConfiguration? {
    return try {
      retrySupport.retry({
        try {
          this.getLaunchConfig(launchConfigurationName)
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
}
