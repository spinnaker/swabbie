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

package com.netflix.spinnaker.janitor.services;

import com.netflix.spinnaker.janitor.model.Account;
import com.netflix.spinnaker.janitor.services.internal.ClouddriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AccountService {
  private static final Logger LOGGER = LoggerFactory.getLogger(AccountService.class);
  private ClouddriverService clouddriverService;

  @Autowired
  public AccountService(ClouddriverService clouddriverService) {
    this.clouddriverService = clouddriverService;
  }

  public List<String> getAccounts() { //TODO add hystrix. ALso maybe bubble up exception
    try {
      Response<List<Account>> response = clouddriverService.getAccounts().execute();
      return response.body()
        .stream()
        .map(Account::getName)
        .collect(Collectors.toList());
    } catch (IOException e) {
      LOGGER.error("Exception getting list of accounts", e);
    }

    return Collections.emptyList();
  }
}
