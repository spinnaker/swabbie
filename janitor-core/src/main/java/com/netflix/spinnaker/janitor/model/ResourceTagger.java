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

package com.netflix.spinnaker.janitor.model;

import java.io.IOException;

/**
 * An interface to tag resources
 */

public interface ResourceTagger {

  /**
   * This prefix is used to identify janitor produced tags
   */

  String JANITOR_PREFIX =  "spinnaker_ui_alert:janitor_marked_for_deletion:";

  /**
   * Janitor's namespace
   */

  String NAMESPACE = "janitor";

  void upsert(EntityTag tag,String resourceId, String resourceType, String account, String region, String cloudProvider) throws IOException;
  EntityTag find(String resourceId, String resourceName, String resourceType) throws IOException;
}
