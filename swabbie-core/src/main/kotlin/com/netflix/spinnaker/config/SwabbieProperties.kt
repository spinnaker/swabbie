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

package com.netflix.spinnaker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("swabbie")
open class SwabbieProperties {
  var dryRun: Boolean = true
  var providers: List<CloudProviderConfiguration> = mutableListOf()
}

class CloudProviderConfiguration {
  var exclusions: MutableList<Exclusion>? = null
  var name: String = ""
  var locations: List<String> = mutableListOf()
  var accounts: List<String> = mutableListOf()
  var resourceTypes: List<ResourceTypeConfiguration> = mutableListOf()
  override fun toString(): String {
    return "CloudProviderConfiguration(exclusions=$exclusions, name='$name', locations=$locations, accounts=$accounts, resourceTypes=$resourceTypes)"
  }
}

class Exclusion {
  var type: String = ""
  var attributes: List<Attribute> = mutableListOf()
  override fun toString(): String {
    return "Exclusion(type='$type', attributes=$attributes)"
  }

  fun withType(t: String): Exclusion =
    this.apply {
      type = t
    }

  fun withAttributes(attrs: List<Attribute>): Exclusion =
    this.apply {
      attributes = attrs
    }
}

class ResourceTypeConfiguration {
  var enabled: Boolean = false
  var dryRun: Boolean = true
  var notifyOwner: Boolean = true
  var retentionDays: Int = 14
  var exclusions: MutableList<Exclusion> = mutableListOf()
  lateinit var name: String
}

class Attribute {
  var key: String = ""
  var value: List<String> = mutableListOf()
  override fun toString(): String {
    return "Attribute(key='$key', value=$value)"
  }

  fun withKey(k: String): Attribute =
    this.apply {
      key = k
    }

  fun withValue(v: List<String>): Attribute =
    this.apply {
      value = v
    }

}

enum class ExclusionType { Name, AccountType, AccountName, Tag }

// TODO: rework this
internal fun mergeExclusions(global: MutableList<Exclusion>?, local: MutableList<Exclusion>?): List<Exclusion> {
  if (global == null && local == null) {
    return emptyList()
  } else if (global == null && local != null) {
    return local
  } else if (global != null && local == null) {
    return global
  }

  // TODO: local is additive to global. local can override global
  return HashSet(global!! + local!!).toList()
}
