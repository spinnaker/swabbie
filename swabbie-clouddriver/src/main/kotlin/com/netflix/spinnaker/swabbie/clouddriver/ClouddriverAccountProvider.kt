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

import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

@ConditionalOnBean(CloudDriverService::class)
@Component
class ClouddriverAccountProvider(
  private val accountCache: InMemoryCache<SpinnakerAccount>
) : AccountProvider {
  override fun getAccounts(): Set<Account> = accountCache.get()
}

@ConditionalOnBean(CloudDriverService::class)
@Component
class ClouddriverAccountCache(
  cloudDriverService: CloudDriverService
) : InMemoryCache<SpinnakerAccount>(cloudDriverService.getAccounts())
