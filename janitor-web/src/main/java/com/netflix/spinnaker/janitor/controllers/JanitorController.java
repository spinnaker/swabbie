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

package com.netflix.spinnaker.janitor.controllers;

import com.netflix.spinnaker.janitor.services.AccountService;
import com.netflix.spinnaker.janitor.queue.MarkMessage;
import com.netflix.spinnaker.janitor.queue.Message;
import com.netflix.spinnaker.janitor.queue.JanitorQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/janitor")
public class JanitorController {

  @Autowired
  private AccountService accountService;

  @Autowired
  private JanitorQueue janitorQueue;

  @Resource
  private Map<String, Integer> resourceTypeToRetentionDays;

  /**
   * The role of this endpoint is to create work by placing items on the work queue
   * Messages will then be picked of the queue for processing
   */

  @RequestMapping(
    value = "/mark",
    method = RequestMethod.POST
  )
  public void mark() {
    List<String> accounts = getAccounts();
    List<Message> messages = buildMessages(
      accounts,
      new ArrayList<>(resourceTypeToRetentionDays.keySet()),
      "aws"
    );

    messages.forEach(message -> janitorQueue.push(message, Duration.ofSeconds(0)));
  }

  private List<String> getAccounts() {
    return accountService.getAccounts();
  }

  /**
   * Builds a mark message with a specific granularity
   * @param accounts list of target accounts
   * @param resourceTypes list of supported resource types
   * @param cloudProvider a cloud provider
   * @return a list of mark messages
   */

  static List<Message> buildMessages(List<String> accounts,
                                     List<String> resourceTypes,
                                     String cloudProvider) {
    List<Message> messages = new ArrayList<>();
    accounts
      .forEach(account -> resourceTypes.forEach(resourceType -> messages.add(
        new MarkMessage(cloudProvider, resourceType, account)
      )));

    return messages;
  }
}
