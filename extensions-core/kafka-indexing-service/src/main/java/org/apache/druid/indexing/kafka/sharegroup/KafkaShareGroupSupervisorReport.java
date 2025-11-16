/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.kafka.sharegroup;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.indexing.overlord.supervisor.SupervisorReport;
import org.apache.druid.indexing.overlord.supervisor.SupervisorStateManager;
import org.joda.time.DateTime;

import java.util.List;

/**
 * Status report for Kafka Share Group supervisor.
 */
public class KafkaShareGroupSupervisorReport extends SupervisorReport
{
  private final Payload payload;

  public KafkaShareGroupSupervisorReport(
      String supervisorId,
      DateTime generationTime,
      String topic,
      String shareGroupId,
      int desiredReplicas,
      int activeTasks,
      SupervisorStateManager.State state,
      List<SupervisorStateManager.ExceptionEvent> recentErrors,
      boolean suspended
  )
  {
    super(supervisorId, generationTime);
    this.payload = new Payload(
        topic,
        shareGroupId,
        desiredReplicas,
        activeTasks,
        state,
        recentErrors,
        suspended
    );
  }

  @Override
  @JsonProperty
  public Object getPayload()
  {
    return payload;
  }

  public static class Payload
  {
    private final String topic;
    private final String shareGroupId;
    private final int desiredReplicas;
    private final int activeTasks;
    private final SupervisorStateManager.State state;
    private final List<SupervisorStateManager.ExceptionEvent> recentErrors;
    private final boolean suspended;

    public Payload(
        String topic,
        String shareGroupId,
        int desiredReplicas,
        int activeTasks,
        SupervisorStateManager.State state,
        List<SupervisorStateManager.ExceptionEvent> recentErrors,
        boolean suspended
    )
    {
      this.topic = topic;
      this.shareGroupId = shareGroupId;
      this.desiredReplicas = desiredReplicas;
      this.activeTasks = activeTasks;
      this.state = state;
      this.recentErrors = recentErrors;
      this.suspended = suspended;
    }

    @JsonProperty
    public String getTopic()
    {
      return topic;
    }

    @JsonProperty
    public String getShareGroupId()
    {
      return shareGroupId;
    }

    @JsonProperty
    public int getDesiredReplicas()
    {
      return desiredReplicas;
    }

    @JsonProperty
    public int getActiveTasks()
    {
      return activeTasks;
    }

    @JsonProperty
    public SupervisorStateManager.State getState()
    {
      return state;
    }

    @JsonProperty
    public List<SupervisorStateManager.ExceptionEvent> getRecentErrors()
    {
      return recentErrors;
    }

    @JsonProperty
    public boolean isSuspended()
    {
      return suspended;
    }
  }
}
