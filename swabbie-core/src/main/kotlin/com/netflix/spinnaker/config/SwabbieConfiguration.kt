/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.config

import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.WorkConfigurationExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.util.ClassUtils
import java.time.Clock

@Configuration
@EnableConfigurationProperties(SwabbieProperties::class)
@ComponentScan(basePackages = arrayOf("com.netflix.spinnaker.swabbie"))
open class SwabbieConfiguration {
  @Autowired
  open fun objectMapper(objectMapper: ObjectMapper) =
    objectMapper.apply {
      registerSubtypes(*findResourceSubtypes())
    }.registerModule(KotlinModule())
      .registerModule(JavaTimeModule())
      .registerModule(resourceDeserializerModule())
      .disable(FAIL_ON_UNKNOWN_PROPERTIES)
      .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS)!!

  @Bean
  open fun clock(): Clock = Clock.systemDefaultZone()

  @Bean
  open fun taskExecutor(): ThreadPoolTaskExecutor =
    ThreadPoolTaskExecutor().apply {
      corePoolSize = 20
    }

  private fun getAccounts(accountProvider: AccountProvider, accountType: String): List<Account> {
    accountProvider.getAccounts().filter {
      it.type.equals(accountType, ignoreCase = true)
    }.let { accounts ->
        return if (accounts.isEmpty()) {
          listOf(EmptyAccount())
        } else {
          accounts
        }
      }
  }

  @Bean
  open fun work(swabbieProperties: SwabbieProperties,
                accountProvider: AccountProvider,
                exclusionPolicies: List<WorkConfigurationExclusionPolicy>): List<Work> {
    val allWork = mutableListOf<Work>()
    log.info("Loading Swabbie configuration {}", swabbieProperties)
    swabbieProperties.providers.forEach { cloudProviderConfiguration ->
      cloudProviderConfiguration.resourceTypes.filter {
        it.enabled
      }.forEach { resourceTypeConfiguration ->
          getAccounts(accountProvider, cloudProviderConfiguration.name).forEach { account ->
          cloudProviderConfiguration.locations.forEach { location ->
            "${cloudProviderConfiguration.name}:${account.name}:$location:${resourceTypeConfiguration.name}".let { namespace ->
              WorkConfiguration(
                namespace = namespace.toLowerCase(),
                account = account,
                location = location,
                cloudProvider = cloudProviderConfiguration.name,
                resourceType = resourceTypeConfiguration.name,
                retentionDays = resourceTypeConfiguration.retentionDays,
                exclusions = mergeExclusions(cloudProviderConfiguration.exclusions, resourceTypeConfiguration.exclusions),
                dryRun = if (swabbieProperties.dryRun) true else (resourceTypeConfiguration.dryRun || swabbieProperties.dryRun)
              ).takeIf {
                !it.shouldBeExcluded(exclusionPolicies, it.exclusions)
              }?.let { configuration ->
                  allWork.add(Work(namespace = namespace.toLowerCase(), configuration = configuration))
                }
            }
          }
        }
      }
    }

    log.info("Generated work {}", allWork)
    return allWork
  }

  private val log: Logger = LoggerFactory.getLogger(javaClass)
}

class ResourceDeserializer : JsonDeserializer<Resource>() {
  private val registry = mutableMapOf<String, Class<*>>()
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Resource =
    (p.codec as ObjectMapper).let { mapper ->
      mapper.readTree<ObjectNode>(p).let { root ->
        root.get(RESOURCE_TYPE_INFO_FIELD).let { node ->
          if (node != null) {
            mapper.convertValue(root, registry[node.asText()]) as Resource
          } else {
            throw IllegalArgumentException("Failed to deserialize subtype")
          }
        }
      }
    }

  fun registerResourceTypes(types: Array<NamedType>): ResourceDeserializer {
    types.forEach { registry[it.name] = it.type as Class<*> }
    return this
  }
}

private val PKG = "com.netflix.spinnaker.swabbie"

private fun findSubTypesInPackage(clazz: Class<Resource>, pkg: String): List<Class<*>> =
  ClassPathScanningCandidateComponentProvider(false)
    .apply { addIncludeFilter(AssignableTypeFilter(clazz)) }
    .findCandidateComponents(pkg)
    .map {
      ClassUtils.resolveClassName(it.beanClassName, ClassUtils.getDefaultClassLoader())
    }

private fun findResourceSubtypes(): Array<NamedType> =
  findSubTypesInPackage(Resource::class.java, PKG)
    .map { c ->
      return@map NamedType(c, c.getAnnotation(JsonTypeName::class.java)?.value)
    }.toTypedArray()

fun resourceDeserializerModule(): SimpleModule =
  SimpleModule("ResourceDeserializerModule")
    .addDeserializer(
      Resource::class.java,
      ResourceDeserializer().registerResourceTypes(
        findResourceSubtypes()
      )
    )
