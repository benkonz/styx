/*-
 * -\-\-
 * Spotify Styx Common
 * --
 * Copyright (C) 2016 - 2023 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

@AutoValue
public abstract class TriggerResponse {
  @JsonProperty
  public abstract WorkflowId workflowId();

  @JsonProperty
  public abstract String parameter();

  @JsonProperty
  public abstract String triggerId();

  @JsonProperty
  public abstract TriggerParameters triggerParameters();

  @JsonCreator
  public static TriggerResponse create(
          @JsonProperty("workflow_id") WorkflowId workflowId,
          @JsonProperty("parameter") String parameter,
          @JsonProperty("trigger_id") String triggerId,
          @JsonProperty("trigger_parameters") TriggerParameters triggerParameters) {
    return of(workflowId, parameter, triggerParameters, triggerId);
  }

  public static TriggerResponse of(WorkflowId workflowId, String parameter,
                            TriggerParameters triggerParameters, String triggerId) {
    return new AutoValue_TriggerResponse(workflowId, parameter, triggerId, triggerParameters);
  }
}
