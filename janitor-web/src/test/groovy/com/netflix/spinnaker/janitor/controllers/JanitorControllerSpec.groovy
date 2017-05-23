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

package com.netflix.spinnaker.janitor.controllers

import com.netflix.spinnaker.janitor.services.AccountService
import com.netflix.spinnaker.janitor.queue.JanitorQueue
import com.netflix.spinnaker.janitor.queue.MarkMessage
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import spock.lang.Specification
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import spock.lang.Subject

class JanitorControllerSpec extends Specification {

  AccountService accountService = Mock(AccountService)
  JanitorQueue janitorQueue = Mock(JanitorQueue)

  @Subject
  JanitorController controller = new JanitorController(
    janitorQueue: janitorQueue,
    accountService: accountService,
    resourceTypeToRetentionDays: [Loadbalancer: 15, ServerGroup: 15]
  )

  def "should build messages with ids of accounts and resource types"() {
    given:
    List<String> resourceTypes = ["LoadBalancer", "ServerGroup"]
    List<String> accounts = ["accountId1"]

    expect:
    JanitorController.buildMessages(accounts, resourceTypes, "aws")*.id == [ "janitor:mark:aws:accountid1:loadbalancer", "janitor:mark:aws:accountid1:servergroup" ]
  }


  def "should add appropriate work to queue for each account and resource type"() {
    given:
    def mockmvc = MockMvcBuilders.standaloneSetup(controller).build()

    and:
    accountService.getAccounts() >> ["accountId1", "accountId2"]

    when:
    mockmvc.perform(
      post("/janitor/mark").contentType(MediaType.APPLICATION_JSON).content('{}')
    ).andReturn()

    then:
    1 * janitorQueue.push({ it.id == "janitor:mark:aws:accountid1:loadbalancer"} as MarkMessage, _)
    1 * janitorQueue.push({ it.id == "janitor:mark:aws:accountid1:servergroup"}  as MarkMessage, _)
    1 * janitorQueue.push({ it.id == "janitor:mark:aws:accountid2:loadbalancer"} as MarkMessage, _)
    1 * janitorQueue.push({ it.id == "janitor:mark:aws:accountid2:servergroup"}  as MarkMessage, _)
  }
}
