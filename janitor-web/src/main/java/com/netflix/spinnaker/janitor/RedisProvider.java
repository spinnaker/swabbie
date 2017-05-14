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

package com.netflix.spinnaker.janitor;

import com.netflix.spinnaker.DataProvider;
import com.netflix.spinnaker.janitor.resource.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RedisProvider<T extends Resource> implements DataProvider<T> {
  private static final String KEY = "com.netflix.spinnaker:janitor:resources";
  private final RedisTemplate<String, T> redisTemplate;

  public RedisProvider(RedisTemplate<String, T> template) {
    this.redisTemplate = template;
  }

  @Override
  public List<T> findAll() {
    List<T> resources = new ArrayList<>();
    redisTemplate
      .opsForHash()
      .scan(KEY, ScanOptions.scanOptions().match("*").build())
      .forEachRemaining( obj -> resources.add((T) obj.getValue()));

    return resources;
  }

  @Override
  public T update(T obj) {
    return null;
  }

  public List<Resource> filter(Map<String, String> params) {
    return null; //TODO: a way to filter the result directly from redis. like find all marked resources or find all resources in region
  }
}
