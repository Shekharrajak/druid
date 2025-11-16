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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.indexing.kafka.supervisor.KafkaSupervisorIOConfig;
import org.apache.druid.indexing.seekablestream.extension.KafkaConfigOverrides;
import org.joda.time.DateTime;
import org.joda.time.Period;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * IOConfig for Share Group supervisor.
 * No partition/offset management - queue-based consumption.
 */
public class KafkaShareGroupSupervisorIOConfig
{
  private final String topic;
  private final String shareGroupId;
  private final InputFormat inputFormat;
  private final Integer replicas;
  private final Period taskDuration;
  private final Map<String, Object> consumerProperties;
  private final long pollTimeout;
  private final Period checkpointPeriod;
  private final Period startDelay;
  private final Period period;
  private final Period completionTimeout;
  private final Period lateMessageRejectionPeriod;
  private final Period earlyMessageRejectionPeriod;
  private final DateTime lateMessageRejectionStartDateTime;
  private final KafkaConfigOverrides configOverrides;

  @JsonCreator
  public KafkaShareGroupSupervisorIOConfig(
      @JsonProperty("topic") String topic,
      @JsonProperty("shareGroupId") String shareGroupId,
      @JsonProperty("inputFormat") InputFormat inputFormat,
      @JsonProperty("replicas") @Nullable Integer replicas,
      @JsonProperty("taskDuration") @Nullable Period taskDuration,
      @JsonProperty("consumerProperties") Map<String, Object> consumerProperties,
      @JsonProperty("pollTimeout") @Nullable Long pollTimeout,
      @JsonProperty("checkpointPeriod") @Nullable Period checkpointPeriod,
      @JsonProperty("startDelay") @Nullable Period startDelay,
      @JsonProperty("period") @Nullable Period period,
      @JsonProperty("completionTimeout") @Nullable Period completionTimeout,
      @JsonProperty("lateMessageRejectionPeriod") @Nullable Period lateMessageRejectionPeriod,
      @JsonProperty("earlyMessageRejectionPeriod") @Nullable Period earlyMessageRejectionPeriod,
      @JsonProperty("lateMessageRejectionStartDateTime") @Nullable DateTime lateMessageRejectionStartDateTime,
      @JsonProperty("configOverrides") @Nullable KafkaConfigOverrides configOverrides
  )
  {
    this.topic = Preconditions.checkNotNull(topic, "topic");
    this.shareGroupId = Preconditions.checkNotNull(shareGroupId, "shareGroupId");
    this.inputFormat = Preconditions.checkNotNull(inputFormat, "inputFormat");
    this.replicas = replicas != null ? replicas : 1;
    this.taskDuration = taskDuration != null ? taskDuration : new Period("PT1H");
    this.consumerProperties = Preconditions.checkNotNull(consumerProperties, "consumerProperties");
    this.pollTimeout = pollTimeout != null ? pollTimeout : KafkaSupervisorIOConfig.DEFAULT_POLL_TIMEOUT_MILLIS;
    this.checkpointPeriod = checkpointPeriod != null ? checkpointPeriod : new Period("PT25S");
    this.startDelay = startDelay != null ? startDelay : new Period("PT5S");
    this.period = period != null ? period : new Period("PT30S");
    this.completionTimeout = completionTimeout != null ? completionTimeout : new Period("PT30M");
    this.lateMessageRejectionPeriod = lateMessageRejectionPeriod;
    this.earlyMessageRejectionPeriod = earlyMessageRejectionPeriod;
    this.lateMessageRejectionStartDateTime = lateMessageRejectionStartDateTime;
    this.configOverrides = configOverrides;
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
  public InputFormat getInputFormat()
  {
    return inputFormat;
  }

  @JsonProperty
  public Integer getReplicas()
  {
    return replicas;
  }

  @JsonProperty
  public Period getTaskDuration()
  {
    return taskDuration;
  }

  @JsonProperty
  public Map<String, Object> getConsumerProperties()
  {
    return consumerProperties;
  }

  @JsonProperty
  public long getPollTimeout()
  {
    return pollTimeout;
  }

  @JsonProperty
  public Period getCheckpointPeriod()
  {
    return checkpointPeriod;
  }

  @JsonProperty
  public Period getStartDelay()
  {
    return startDelay;
  }

  @JsonProperty
  public Period getPeriod()
  {
    return period;
  }

  @JsonProperty
  public Period getCompletionTimeout()
  {
    return completionTimeout;
  }

  @JsonProperty
  @Nullable
  public Period getLateMessageRejectionPeriod()
  {
    return lateMessageRejectionPeriod;
  }

  @JsonProperty
  @Nullable
  public Period getEarlyMessageRejectionPeriod()
  {
    return earlyMessageRejectionPeriod;
  }

  @JsonProperty
  @Nullable
  public DateTime getLateMessageRejectionStartDateTime()
  {
    return lateMessageRejectionStartDateTime;
  }

  @JsonProperty
  @Nullable
  public KafkaConfigOverrides getConfigOverrides()
  {
    return configOverrides;
  }

  @Override
  public String toString()
  {
    return "KafkaShareGroupSupervisorIOConfig{" +
           "topic='" + topic + '\'' +
           ", shareGroupId='" + shareGroupId + '\'' +
           ", inputFormat=" + inputFormat +
           ", replicas=" + replicas +
           ", taskDuration=" + taskDuration +
           ", pollTimeout=" + pollTimeout +
           ", checkpointPeriod=" + checkpointPeriod +
           ", startDelay=" + startDelay +
           ", period=" + period +
           ", completionTimeout=" + completionTimeout +
           ", lateMessageRejectionPeriod=" + lateMessageRejectionPeriod +
           ", earlyMessageRejectionPeriod=" + earlyMessageRejectionPeriod +
           ", lateMessageRejectionStartDateTime=" + lateMessageRejectionStartDateTime +
           ", configOverrides=" + configOverrides +
           '}';
  }
}
