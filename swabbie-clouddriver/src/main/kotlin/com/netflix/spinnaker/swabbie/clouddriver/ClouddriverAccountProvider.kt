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

package com.netflix.spinnaker.swabbie.clouddriver

import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.AccountProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

@Component
class ClouddriverAccountProvider(
  private val cloudDriverService: CloudDriverService
) : AccountProvider {
  private val log: Logger = LoggerFactory.getLogger(javaClass)
  private var accountsCache = AtomicReference<Set<Account>>()
  override fun getAccounts(): Set<Account> {
    return cloudDriverService.getAccounts()
  }

  @Scheduled(fixedDelay = 24 * 60 * 60 * 1000L)
  private fun refreshApplications() {
    try {
      accountsCache.set(cloudDriverService.getAccounts())
    } catch (e: Exception) {
      log.error("Error refreshing accounts cache", e)
    }
  }
}
