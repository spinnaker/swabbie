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
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.edda.EddaService
import com.netflix.spinnaker.swabbie.model.IMAGE
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import retrofit.RetrofitError

@Component
open class EddaAmazonImageProvider(
  private val eddaApiClients: List<EddaApiClient>,
  private val retrySupport: RetrySupport,
  private val registry: Registry
) : ResourceProvider<AmazonImage>, EddaApiSupport(eddaApiClients, registry) {
  private val log: Logger = LoggerFactory.getLogger(javaClass)

  override fun getAll(params: Parameters): List<AmazonImage>? {
    withEddaClient(
      region = params["region"] as String,
      accountId = params["account"] as String,
      environment = params["environment"] as String
    )?.run {
      return getAmis()
    }

    return emptyList()
  }

  override fun getOne(params: Parameters): AmazonImage? {
    withEddaClient(
      region = params["region"] as String,
      accountId = params["account"] as String,
      environment = params["environment"] as String
    )?.run {
      return getAmi(params["imageId"] as String)
    }

    return null
  }

  private fun EddaService.getAmis(): List<AmazonImage> {
    return try {
      retrySupport.retry({
        this.getImages()
      }, maxRetries, retryBackOffMillis, true)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", IMAGE)).increment()
      log.error("failed to get images", e)
      throw e
    }
  }

  private fun EddaService.getAmi(imageId: String): AmazonImage? {
    return try {
      retrySupport.retry({
        try {
          this.getImage(imageId)
        } catch (e: Exception) {
          if (e is RetrofitError && e.response.status == 404) {
            null
          } else {
            throw e
          }
        }
      }, maxRetries, retryBackOffMillis, false)
    } catch (e: Exception) {
      registry.counter(eddaFailureCountId.withTags("resourceType", IMAGE)).increment()
      log.error("failed to get image {}", imageId, e)
      throw e
    }
  }
}
