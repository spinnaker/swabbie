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

package com.netflix.spinnaker.janitor.handlers;


import com.netflix.spinnaker.janitor.model.LoadBalancer;
import com.netflix.spinnaker.janitor.model.ResourceTagger;
import com.netflix.spinnaker.janitor.provider.DataProvider;
import com.netflix.spinnaker.janitor.queue.*;
import com.netflix.spinnaker.janitor.rulesengine.RulesEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

@Component
public class LoadBalancerHandler extends JanitorSupport implements MessageHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(LoadBalancerHandler.class);
  private DataProvider<LoadBalancer> loadBalancerDataProvider;

  @Autowired
  public LoadBalancerHandler(DataProvider<LoadBalancer> loadBalancerDataProvider) {
    this.loadBalancerDataProvider =loadBalancerDataProvider;
  }

  @Override
  public boolean supports(Message message) {
    return LoadBalancer.class.getSimpleName().equalsIgnoreCase(message.getResourceType());
  }

  @Override
  public void handleCleanup(Message message, ResourceTagger resourceTagger, Clock clock) throws Exception {
    CleanupMessage cleanupMessage = (CleanupMessage) message;
    cleanupResource(cleanupMessage, resourceTagger, clock,
      m -> loadBalancerDataProvider.remove(cleanupMessage.getCloudProvider(), cleanupMessage.getAccount(), cleanupMessage.getRegion(), cleanupMessage.getResourceName())
    );
  }

  @Override
  public void handleMark(Message message, RulesEngine rulesEngine, ResourceTagger resourceTagger, Clock clock, JanitorQueue janitorQueue) throws Exception {
    List<LoadBalancer> loadBalancers = loadBalancerDataProvider.findByAccount(message.getAccount());
    for (LoadBalancer loadBalancer : loadBalancers) {
      try {
        markResource(message, loadBalancer, rulesEngine, resourceTagger, clock, janitorQueue);
      } catch (IOException e) { //TODO: revisit this, might be worth letting that exceptoin bubble up and handled by a consumer
        LOGGER.error("failed to handle load balancer resource {}", loadBalancer);
      }
    }
  }
}
