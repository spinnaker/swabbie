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

package com.netflix.spinnaker.swabbie.aws.handlers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.netflix.spinnaker.swabbie.Notifier
import com.netflix.spinnaker.swabbie.ResourceRepository
import com.netflix.spinnaker.swabbie.aws.model.AmazonSecurityGroup
import com.netflix.spinnaker.swabbie.aws.provider.AmazonSecurityGroupProvider
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.scheduler.MarkResourceDescription
import com.netflix.spinnaker.swabbie.scheduler.RetentionPolicy
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock

object AmazonSecurityGroupHandlerTest {
  val resourceRepository = mock<ResourceRepository>()
  val notifier = mock<Notifier>()
  val clock = Clock.systemDefaultZone()
  val objectMapper = ObjectMapper().apply {
    registerSubtypes(AmazonSecurityGroup::class.java)
    registerModule(KotlinModule())
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
  }

  val amazonSecurityGroupProvider = mock<AmazonSecurityGroupProvider>()

  @AfterEach
  fun cleanup() {
    reset(resourceRepository, notifier)
  }

  @Test
  fun `should handle security groups`() {
    val rule = mock<Rule>()
    whenever(notifier.notify(any(), any())) doReturn
      Notification(clock.millis(), "yolo@netflixcom", "Email" )

    whenever(amazonSecurityGroupProvider.getSecurityGroups(any())) doReturn
      getSecurityGroups()

    whenever(rule.applies(any())) doReturn true
    whenever(rule.apply(any())) doReturn
      Result(Summary("invalid resource", "rule1"))

    AmazonSecurityGroupHandler(
      listOf(rule),
      resourceRepository,
      notifier,
      amazonSecurityGroupProvider
    ).mark(
      MarkResourceDescription(
        "aws:test:us-east-1",
        SECURITY_GROUP,
        "aws",
        RetentionPolicy(null, 10)
      )
    )

    verify(notifier).notify(any(), any())
    verify(resourceRepository).track(any(), any())
  }

  private fun getSecurityGroups(): List<AmazonSecurityGroup> {
    val stream = ClassLoader.getSystemClassLoader().getResourceAsStream("securityGroup.json")
    val json = stream.bufferedReader().use { it.readText() }
    val map = objectMapper.readValue(json, MutableMap::class.java)
    val str = objectMapper.writeValueAsString(map + mapOf("type" to "amazonSecurityGroup"))

    return listOf(objectMapper.readValue(str, AmazonSecurityGroup::class.java))
  }
}
