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

package com.netflix.spinnaker.janitor.services.internal;

import com.netflix.spinnaker.janitor.model.EntityTag;
import retrofit.http.*;
import retrofit2.Call;

import java.util.List;


public interface TagService {
  @Headers("Accept: application/json")
  @POST("/tags")
  Call<Void> add(@Query("entityId") String entityId,
                 @Query("entityType") String entityType,
                 @Query("account") String account,
                 @Query("region") String region,
                 @Query("cloudProvider") String cloudProvider,
                 @Body List<EntityTag> tag
  );

  @Headers("Accept: application/json")
  @GET("/tags")
  Call<List<EntityTag>> find(@Query("entityId") String resourceId, @Query("entityType") String resourceType);
}
