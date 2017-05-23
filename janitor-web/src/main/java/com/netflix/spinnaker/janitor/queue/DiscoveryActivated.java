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


import com.netflix.appinfo.InstanceInfo;
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class to determine if the current instance is UP or Down in Discovery
 */

public class DiscoveryActivated implements ApplicationListener<RemoteStatusChangedEvent> {
  private static Logger LOGGER = LoggerFactory.getLogger(DiscoveryActivated.class);
  private AtomicBoolean enabled  = new AtomicBoolean(true);

  @Override
  public void onApplicationEvent(RemoteStatusChangedEvent event) {
    if (event.getSource().isUp()) {
      LOGGER.info("Instance is Up");
      enabled.set(true);
    } else if (event.getSource().getPreviousStatus() == InstanceInfo.InstanceStatus.UP) {
      LOGGER.info("Instance is DOWN");
      enabled.set(false);
    }
  }

  boolean getEnabled() {
    return enabled.get();
  }
}
