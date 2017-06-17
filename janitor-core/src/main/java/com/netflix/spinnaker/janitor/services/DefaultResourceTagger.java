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

import com.netflix.spinnaker.janitor.model.EntityTag;
import com.netflix.spinnaker.janitor.model.ResourceTagger;
import com.netflix.spinnaker.janitor.services.internal.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;

@Component
public class DefaultResourceTagger implements ResourceTagger {
  private final TagService tagService;

  @Autowired
  public DefaultResourceTagger(TagService tagService) {
    this.tagService = tagService;
  }

  @Override
  public void upsert(EntityTag tag,
                     String resourceId,
                     String resourceType,
                     String account,
                     String region,
                     String cloudProvider) {
    tag.setNamespace(NAMESPACE);
    tagService.add(resourceId, resourceType.toLowerCase(), account, region, cloudProvider, Collections.singletonList(tag));
  }

  @Override
  public EntityTag find(String resourceId, String resourceName, String resourceType) {
    List<EntityTag> result = tagService.find(resourceId, resourceType.toLowerCase());
    return result
      .stream()
      .filter(e -> e.getName().equals(resourceName))
      .findFirst()
      .orElse(null);
  }
}
