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

package com.netflix.spinnaker.janitor.provider;
import com.netflix.spinnaker.janitor.model.Resource;

import java.util.List;

/**
 * A resource data provider
 * @param <T>
 */

public interface DataProvider<T extends Resource> {

  /**
   * Gets resources by account
   * @param account the account the resource belongs in
   * @return a list of resources
   */

  List<T> findByAccount(String account);

  /**
   * Deletes a resource
   * @param cloudProvider (aws|...)
   * @param account target account
   * @param region target region
   * @param name the name of the resource
   */

  void remove(String cloudProvider, String account, String region, String name);
}
