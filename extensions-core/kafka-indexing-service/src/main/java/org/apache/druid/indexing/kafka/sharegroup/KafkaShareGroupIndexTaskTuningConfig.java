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
import org.joda.time.Period;

import javax.annotation.Nullable;
import java.io.File;

/**
 * Tuning config for Share Group ingestion tasks.
 */
public class KafkaShareGroupIndexTaskTuningConfig
{
  private static final int DEFAULT_MAX_ROWS_IN_MEMORY = 150000;
  private static final Period DEFAULT_INTERMEDIATE_PERSIST_PERIOD = new Period("PT10M");
  private static final int DEFAULT_MAX_PENDING_PERSISTS = 0;
  private static final long DEFAULT_HANDOFF_CONDITION_TIMEOUT = 0L;
  private static final int DEFAULT_MAX_PARSE_EXCEPTIONS = Integer.MAX_VALUE;
  private static final int DEFAULT_MAX_SAVED_PARSE_EXCEPTIONS = 0;

  private final AppendableIndexSpec appendableIndexSpec;
  private final Integer maxRowsInMemory;
  private final Long maxBytesInMemory;
  private final Boolean skipBytesInMemoryOverheadCheck;
  private final Integer maxRowsPerSegment;
  private final Long maxTotalRows;
  private final Period intermediatePersistPeriod;
  private final File basePersistDirectory;
  private final Integer maxPendingPersists;
  private final IndexSpec indexSpec;
  private final IndexSpec indexSpecForIntermediatePersists;
  private final Boolean logParseExceptions;
  private final Integer maxParseExceptions;
  private final Integer maxSavedParseExceptions;
  private final Long handoffConditionTimeout;
  private final SegmentWriteOutMediumFactory segmentWriteOutMediumFactory;
  private final Period intermediateHandoffPeriod;
  private final Integer numPersistThreads;
  private final Integer maxColumnsToMerge;

  @JsonCreator
  public KafkaShareGroupIndexTaskTuningConfig(
      @JsonProperty("appendableIndexSpec") @Nullable AppendableIndexSpec appendableIndexSpec,
      @JsonProperty("maxRowsInMemory") @Nullable Integer maxRowsInMemory,
      @JsonProperty("maxBytesInMemory") @Nullable Long maxBytesInMemory,
      @JsonProperty("skipBytesInMemoryOverheadCheck") @Nullable Boolean skipBytesInMemoryOverheadCheck,
      @JsonProperty("maxRowsPerSegment") @Nullable Integer maxRowsPerSegment,
      @JsonProperty("maxTotalRows") @Nullable Long maxTotalRows,
      @JsonProperty("intermediatePersistPeriod") @Nullable Period intermediatePersistPeriod,
      @JsonProperty("basePersistDirectory") @Nullable File basePersistDirectory,
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
      @JsonProperty("maxColumnsToMerge") @Nullable Integer maxColumnsToMerge
  )
  {
    this.appendableIndexSpec = appendableIndexSpec;
    this.maxRowsInMemory = maxRowsInMemory != null ? maxRowsInMemory : DEFAULT_MAX_ROWS_IN_MEMORY;
    this.maxBytesInMemory = maxBytesInMemory != null ? maxBytesInMemory : 0L;
    this.skipBytesInMemoryOverheadCheck = skipBytesInMemoryOverheadCheck != null
                                           ? skipBytesInMemoryOverheadCheck
                                           : false;
    this.maxRowsPerSegment = maxRowsPerSegment;
    this.maxTotalRows = maxTotalRows;
    this.intermediatePersistPeriod = intermediatePersistPeriod != null
                                      ? intermediatePersistPeriod
                                      : DEFAULT_INTERMEDIATE_PERSIST_PERIOD;
    this.basePersistDirectory = basePersistDirectory;
    this.maxPendingPersists = maxPendingPersists != null ? maxPendingPersists : DEFAULT_MAX_PENDING_PERSISTS;
    this.indexSpec = indexSpec;
    this.indexSpecForIntermediatePersists = indexSpecForIntermediatePersists;
    this.logParseExceptions = logParseExceptions != null ? logParseExceptions : false;
    this.maxParseExceptions = maxParseExceptions != null ? maxParseExceptions : DEFAULT_MAX_PARSE_EXCEPTIONS;
    this.maxSavedParseExceptions = maxSavedParseExceptions != null
                                    ? maxSavedParseExceptions
                                    : DEFAULT_MAX_SAVED_PARSE_EXCEPTIONS;
    this.handoffConditionTimeout = handoffConditionTimeout != null
                                    ? handoffConditionTimeout
                                    : DEFAULT_HANDOFF_CONDITION_TIMEOUT;
    this.segmentWriteOutMediumFactory = segmentWriteOutMediumFactory;
    this.intermediateHandoffPeriod = intermediateHandoffPeriod;
    this.numPersistThreads = numPersistThreads != null ? numPersistThreads : 1;
    this.maxColumnsToMerge = maxColumnsToMerge;
  }

  @JsonProperty
  @Nullable
  public AppendableIndexSpec getAppendableIndexSpec()
  {
    return appendableIndexSpec;
  }

  @JsonProperty
  public Integer getMaxRowsInMemory()
  {
    return maxRowsInMemory;
  }

  @JsonProperty
  public Long getMaxBytesInMemory()
  {
    return maxBytesInMemory;
  }

  @JsonProperty
  public Boolean isSkipBytesInMemoryOverheadCheck()
  {
    return skipBytesInMemoryOverheadCheck;
  }

  @JsonProperty
  @Nullable
  public Integer getMaxRowsPerSegment()
  {
    return maxRowsPerSegment;
  }

  @JsonProperty
  @Nullable
  public Long getMaxTotalRows()
  {
    return maxTotalRows;
  }

  @JsonProperty
  public Period getIntermediatePersistPeriod()
  {
    return intermediatePersistPeriod;
  }

  @Nullable
  public File getBasePersistDirectory()
  {
    return basePersistDirectory;
  }

  @JsonProperty
  public Integer getMaxPendingPersists()
  {
    return maxPendingPersists;
  }

  @JsonProperty
  @Nullable
  public IndexSpec getIndexSpec()
  {
    return indexSpec;
  }

  @JsonProperty
  @Nullable
  public IndexSpec getIndexSpecForIntermediatePersists()
  {
    return indexSpecForIntermediatePersists;
  }

  @JsonProperty
  public Boolean isLogParseExceptions()
  {
    return logParseExceptions;
  }

  @JsonProperty
  public Integer getMaxParseExceptions()
  {
    return maxParseExceptions;
  }

  @JsonProperty
  public Integer getMaxSavedParseExceptions()
  {
    return maxSavedParseExceptions;
  }

  @JsonProperty
  public Long getHandoffConditionTimeout()
  {
    return handoffConditionTimeout;
  }

  @JsonProperty
  @Nullable
  public SegmentWriteOutMediumFactory getSegmentWriteOutMediumFactory()
  {
    return segmentWriteOutMediumFactory;
  }

  @JsonProperty
  @Nullable
  public Period getIntermediateHandoffPeriod()
  {
    return intermediateHandoffPeriod;
  }

  @JsonProperty
  public Integer getNumPersistThreads()
  {
    return numPersistThreads;
  }

  @JsonProperty
  @Nullable
  public Integer getMaxColumnsToMerge()
  {
    return maxColumnsToMerge;
  }

  public KafkaShareGroupIndexTaskTuningConfig withBasePersistDirectory(File dir)
  {
    return new KafkaShareGroupIndexTaskTuningConfig(
        appendableIndexSpec,
        maxRowsInMemory,
        maxBytesInMemory,
        skipBytesInMemoryOverheadCheck,
        maxRowsPerSegment,
        maxTotalRows,
        intermediatePersistPeriod,
        dir,
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
  }

  @Override
  public String toString()
  {
    return "KafkaShareGroupIndexTaskTuningConfig{" +
           "appendableIndexSpec=" + appendableIndexSpec +
           ", maxRowsInMemory=" + maxRowsInMemory +
           ", maxBytesInMemory=" + maxBytesInMemory +
           ", skipBytesInMemoryOverheadCheck=" + skipBytesInMemoryOverheadCheck +
           ", maxRowsPerSegment=" + maxRowsPerSegment +
           ", maxTotalRows=" + maxTotalRows +
           ", intermediatePersistPeriod=" + intermediatePersistPeriod +
           ", basePersistDirectory=" + basePersistDirectory +
           ", maxPendingPersists=" + maxPendingPersists +
           ", indexSpec=" + indexSpec +
           ", indexSpecForIntermediatePersists=" + indexSpecForIntermediatePersists +
           ", logParseExceptions=" + logParseExceptions +
           ", maxParseExceptions=" + maxParseExceptions +
           ", maxSavedParseExceptions=" + maxSavedParseExceptions +
           ", handoffConditionTimeout=" + handoffConditionTimeout +
           ", segmentWriteOutMediumFactory=" + segmentWriteOutMediumFactory +
           ", intermediateHandoffPeriod=" + intermediateHandoffPeriod +
           ", numPersistThreads=" + numPersistThreads +
           ", maxColumnsToMerge=" + maxColumnsToMerge +
           '}';
  }
}
