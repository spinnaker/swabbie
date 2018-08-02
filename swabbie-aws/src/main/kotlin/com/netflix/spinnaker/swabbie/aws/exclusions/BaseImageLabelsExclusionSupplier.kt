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

package com.netflix.spinnaker.swabbie.aws.exclusions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.config.Attribute
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.exclusions.ExclusionsSupplier
import com.netflix.spinnaker.swabbie.model.DynamicProperty
import com.netflix.spinnaker.swabbie.services.DynamicPropertyService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression("\${mahe.enabled}")
class BaseImageLabelsExclusionSupplier(
  private val objectMapper: ObjectMapper,
  private val props: InMemoryCache<DynamicProperty>
) : ExclusionsSupplier {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  override fun get(): List<Exclusion> {
    val exclusions = mutableListOf<Exclusion>()
    props.get().find { it.key == baseAmiKey }?.let { prop ->
      val value = objectMapper.readValue<Map<String, Map<String, Any>>>(prop.value as String)
      value.map {
        it.value.values
      }.flatten()
        .toSet().let { labels ->
          exclusions.add(
            Exclusion()
              .withType(ExclusionType.Literal.toString())
              .withAttributes(
                listOf(
                  Attribute()
                    .withKey("name")
                    .withValue(
                      labels.toList() as List<String>
                    )
                )
              )
          )
        }
    }

    log.debug("Base Image exclusions {}", exclusions)
    return exclusions
  }
}

@Component
@ConditionalOnExpression("\${mahe.enabled}")
class BaseImageLabelsCache(
  gateService: DynamicPropertyService
) : InMemoryCache<DynamicProperty>(
  gateService.getProperties("bakery").propertiesList.toSet()
)

//TODO: make configurable
const val baseAmiKey = "bakery.api.base_label_map"
