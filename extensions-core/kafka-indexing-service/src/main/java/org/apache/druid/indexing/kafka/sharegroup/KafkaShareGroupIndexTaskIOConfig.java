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
 * IOConfig for Share Group ingestion tasks.
 * No partition assignment - tasks consume from shared queue.
 */
public class KafkaShareGroupIndexTaskIOConfig
{
  private final String topic;
  private final String shareGroupId;
  private final Map<String, Object> consumerProperties;
  private final long pollTimeout;
  private final Period checkpointPeriod;
  private final boolean useTransaction;
  private final DateTime minimumMessageTime;
  private final DateTime maximumMessageTime;
  private final InputFormat inputFormat;
  private final KafkaConfigOverrides configOverrides;

  @JsonCreator
  public KafkaShareGroupIndexTaskIOConfig(
      @JsonProperty("topic") String topic,
      @JsonProperty("shareGroupId") String shareGroupId,
      @JsonProperty("consumerProperties") Map<String, Object> consumerProperties,
      @JsonProperty("pollTimeout") @Nullable Long pollTimeout,
      @JsonProperty("checkpointPeriod") @Nullable Period checkpointPeriod,
      @JsonProperty("useTransaction") @Nullable Boolean useTransaction,
      @JsonProperty("minimumMessageTime") @Nullable DateTime minimumMessageTime,
      @JsonProperty("maximumMessageTime") @Nullable DateTime maximumMessageTime,
      @JsonProperty("inputFormat") @Nullable InputFormat inputFormat,
      @JsonProperty("configOverrides") @Nullable KafkaConfigOverrides configOverrides
  )
  {
    this.topic = Preconditions.checkNotNull(topic, "topic");
    this.shareGroupId = Preconditions.checkNotNull(shareGroupId, "shareGroupId");
    this.consumerProperties = Preconditions.checkNotNull(consumerProperties, "consumerProperties");
    this.pollTimeout = pollTimeout != null ? pollTimeout : KafkaSupervisorIOConfig.DEFAULT_POLL_TIMEOUT_MILLIS;
    this.checkpointPeriod = checkpointPeriod != null ? checkpointPeriod : new Period("PT25S");
    this.useTransaction = useTransaction != null ? useTransaction : true;
    this.minimumMessageTime = minimumMessageTime;
    this.maximumMessageTime = maximumMessageTime;
    this.inputFormat = inputFormat;
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
  public boolean isUseTransaction()
  {
    return useTransaction;
  }

  @JsonProperty
  @Nullable
  public DateTime getMinimumMessageTime()
  {
    return minimumMessageTime;
  }

  @JsonProperty
  @Nullable
  public DateTime getMaximumMessageTime()
  {
    return maximumMessageTime;
  }

  @JsonProperty
  @Nullable
  public InputFormat getInputFormat()
  {
    return inputFormat;
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
    return "KafkaShareGroupIndexTaskIOConfig{" +
           "topic='" + topic + '\'' +
           ", shareGroupId='" + shareGroupId + '\'' +
           ", pollTimeout=" + pollTimeout +
           ", checkpointPeriod=" + checkpointPeriod +
           ", useTransaction=" + useTransaction +
           ", minimumMessageTime=" + minimumMessageTime +
           ", maximumMessageTime=" + maximumMessageTime +
           ", inputFormat=" + inputFormat +
           ", configOverrides=" + configOverrides +
           '}';
  }
}
