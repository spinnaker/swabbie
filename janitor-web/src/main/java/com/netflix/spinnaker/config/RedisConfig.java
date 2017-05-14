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

package com.netflix.spinnaker.config;

import com.netflix.spinnaker.janitor.RedisProvider;
import com.netflix.spinnaker.janitor.resource.Resource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableConfigurationProperties(RedisConfigurationProperties.class)
public class RedisConfig {
  @Bean
  public RedisProvider<Resource> resourceProvider(RedisTemplate<String, Resource> template) {
    return new RedisProvider<>(template);
  }

  @Bean
  public RedisConnectionFactory jedisConnectionFactory(RedisConfigurationProperties redisConfigurationProperties) {
    JedisConnectionFactory factory = new JedisConnectionFactory();
    factory.setHostName(redisConfigurationProperties.getConnection());
    factory.setPort(redisConfigurationProperties.getPort());
    factory.setTimeout(redisConfigurationProperties.getTimeout());
    return factory;
  }

  @Bean
  public RedisTemplate<String, Resource> resourceRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
    StringRedisSerializer serializer = new StringRedisSerializer();
    RedisTemplate<String, Resource> template = new RedisTemplate<>();
    template.setConnectionFactory(redisConnectionFactory);
    template.setKeySerializer(serializer);
    template.setHashKeySerializer(serializer);
    template.setHashValueSerializer(new Jackson2JsonRedisSerializer<>(Resource.class));
    return template;
  }
}
