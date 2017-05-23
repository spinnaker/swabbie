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
 * An abstract janitor that can mark and cleanup a resource
 */

public interface Janitor {

  /**
   * Marks resources
   * @param message contains instructions on how to mark resources
   */

  void mark(Message message);

  /**
   * Cleans a resource
   * @param message contains instructions on how to clean a single resource
   * A clean message has information on what resource to clean
   */

  void cleanup(Message message);
}
