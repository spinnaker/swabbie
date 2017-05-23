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

package com.netflix.spinnaker.janitor.queue;

/**
 * A message with resource marking semantics
 * Each message contains the granularity for each resource mark
 */

public class MarkMessage extends Message {
  public MarkMessage(String cloudProvider, String resourceType, String account) {
    super(cloudProvider, resourceType, account);
  }

  @Override
  void match(Janitor visitor) {
    visitor.mark(this);
  }

  @Override
  String getAction() {
    return "mark";
  }
}
