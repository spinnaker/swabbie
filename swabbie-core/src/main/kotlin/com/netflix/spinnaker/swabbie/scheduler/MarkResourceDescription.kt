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

package com.netflix.spinnaker.swabbie.scheduler

data class MarkResourceDescription(
  val namespace: String,
  val resourceType: String,
  val cloudProvider: String,
  val retentionPolicy: RetentionPolicy
): WorkDescription

interface WorkDescription

data class RetentionPolicy(
  val exclusions: List<String>?,
  val days: Int
)
