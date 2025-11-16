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
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.incremental.AppendableIndexSpec;
import org.apache.druid.segment.writeout.SegmentWriteOutMediumFactory;
import org.joda.time.Duration;
import org.joda.time.Period;

import javax.annotation.Nullable;

/**
 * Tuning config for Share Group supervisor.
 */
public class KafkaShareGroupSupervisorTuningConfig extends KafkaShareGroupIndexTaskTuningConfig
{
  private static final Duration DEFAULT_HTTP_TIMEOUT = new Duration(Duration.standardSeconds(10));
  private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = new Duration(Duration.standardSeconds(80));
  private static final long DEFAULT_CHAT_RETRIES = 8;

  private final Integer workerThreads;
  private final Long chatRetries;
  private final Duration httpTimeout;
  private final Duration shutdownTimeout;

  public static KafkaShareGroupSupervisorTuningConfig defaultConfig()
  {
    return new KafkaShareGroupSupervisorTuningConfig(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
    );
  }

  @JsonCreator
  public KafkaShareGroupSupervisorTuningConfig(
      @JsonProperty("appendableIndexSpec") @Nullable AppendableIndexSpec appendableIndexSpec,
      @JsonProperty("maxRowsInMemory") @Nullable Integer maxRowsInMemory,
      @JsonProperty("maxBytesInMemory") @Nullable Long maxBytesInMemory,
      @JsonProperty("skipBytesInMemoryOverheadCheck") @Nullable Boolean skipBytesInMemoryOverheadCheck,
      @JsonProperty("maxRowsPerSegment") @Nullable Integer maxRowsPerSegment,
      @JsonProperty("maxTotalRows") @Nullable Long maxTotalRows,
      @JsonProperty("intermediatePersistPeriod") @Nullable Period intermediatePersistPeriod,
      @JsonProperty("maxPendingPersists") @Nullable Integer maxPendingPersists,
      @JsonProperty("indexSpec") @Nullable IndexSpec indexSpec,
      @JsonProperty("indexSpecForIntermediatePersists") @Nullable IndexSpec indexSpecForIntermediatePersists,
      @JsonProperty("logParseExceptions") @Nullable Boolean logParseExceptions,
      @JsonProperty("maxParseExceptions") @Nullable Integer maxParseExceptions,
      @JsonProperty("maxSavedParseExceptions") @Nullable Integer maxSavedParseExceptions,
      @JsonProperty("handoffConditionTimeout") @Nullable Long handoffConditionTimeout,
      @JsonProperty("segmentWriteOutMediumFactory") @Nullable SegmentWriteOutMediumFactory segmentWriteOutMediumFactory,
      @JsonProperty("intermediateHandoffPeriod") @Nullable Period intermediateHandoffPeriod,
      @JsonProperty("numPersistThreads") @Nullable Integer numPersistThreads,
      @JsonProperty("maxColumnsToMerge") @Nullable Integer maxColumnsToMerge,
      @JsonProperty("workerThreads") @Nullable Integer workerThreads,
      @JsonProperty("chatRetries") @Nullable Long chatRetries,
      @JsonProperty("httpTimeout") @Nullable Period httpTimeout,
      @JsonProperty("shutdownTimeout") @Nullable Period shutdownTimeout
  )
  {
    super(
        appendableIndexSpec,
        maxRowsInMemory,
        maxBytesInMemory,
        skipBytesInMemoryOverheadCheck,
        maxRowsPerSegment,
        maxTotalRows,
        intermediatePersistPeriod,
        null,
        maxPendingPersists,
        indexSpec,
        indexSpecForIntermediatePersists,
        logParseExceptions,
        maxParseExceptions,
        maxSavedParseExceptions,
        handoffConditionTimeout,
        segmentWriteOutMediumFactory,
        intermediateHandoffPeriod,
        numPersistThreads,
        maxColumnsToMerge
    );

    this.workerThreads = workerThreads;
    this.chatRetries = chatRetries != null ? chatRetries : DEFAULT_CHAT_RETRIES;
    this.httpTimeout = httpTimeout != null ? new Duration(httpTimeout.toStandardDuration()) : DEFAULT_HTTP_TIMEOUT;
    this.shutdownTimeout = shutdownTimeout != null ? new Duration(shutdownTimeout.toStandardDuration()) : DEFAULT_SHUTDOWN_TIMEOUT;
  }

  @JsonProperty
  @Nullable
  public Integer getWorkerThreads()
  {
    return workerThreads;
  }

  @JsonProperty
  public Long getChatRetries()
  {
    return chatRetries;
  }

  @JsonProperty
  public Duration getHttpTimeout()
  {
    return httpTimeout;
  }

  @JsonProperty
  public Duration getShutdownTimeout()
  {
    return shutdownTimeout;
  }

  public KafkaShareGroupIndexTaskTuningConfig convertToTaskTuningConfig()
  {
    return new KafkaShareGroupIndexTaskTuningConfig(
        getAppendableIndexSpec(),
        getMaxRowsInMemory(),
        getMaxBytesInMemory(),
        isSkipBytesInMemoryOverheadCheck(),
        getMaxRowsPerSegment(),
        getMaxTotalRows(),
        getIntermediatePersistPeriod(),
        null,
        getMaxPendingPersists(),
        getIndexSpec(),
        getIndexSpecForIntermediatePersists(),
        isLogParseExceptions(),
        getMaxParseExceptions(),
        getMaxSavedParseExceptions(),
        getHandoffConditionTimeout(),
        getSegmentWriteOutMediumFactory(),
        getIntermediateHandoffPeriod(),
        getNumPersistThreads(),
        getMaxColumnsToMerge()
    );
  }

  @Override
  public String toString()
  {
    return "KafkaShareGroupSupervisorTuningConfig{" +
           "workerThreads=" + workerThreads +
           ", chatRetries=" + chatRetries +
           ", httpTimeout=" + httpTimeout +
           ", shutdownTimeout=" + shutdownTimeout +
           ", parent=" + super.toString() +
           '}';
  }
}
