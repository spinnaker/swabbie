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

package com.netflix.spinnaker.swabbie.handlers

import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary

abstract class AbstractResourceHandler(
  private val rules: List<Rule>
): ResourceHandler {
  override fun createWork() {
    val resources: List<Resource> = getResources()
    filter(resources).forEach { resource ->
      val summaries = mutableListOf<Summary>()
      rules
        .filter { it.applies(resource) }
        .map { it.apply(resource) }
        .forEach {
          if (it.summary != null) {
            summaries += it.summary
          }
        }

      if (!summaries.isEmpty()) {
        // TODO: resource violated some rule, we need to do something
      } else {
        // TODO: ensure resource is unmarked because it's valid
      }
    }
  }

  abstract fun getResources(): List<Resource>
  abstract fun filter(resources: List<Resource>): List<Resource>
}
