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

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.kafka.KafkaRecordEntity;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexing.appenderator.ActionBasedUsedSegmentChecker;
import org.apache.druid.indexing.common.TaskLockHelper;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.task.AbstractTask;
import org.apache.druid.indexing.common.task.BatchAppenderators;
import org.apache.druid.indexing.common.task.SegmentAllocatorForBatch;
import org.apache.druid.indexing.common.task.SegmentAllocators;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.seekablestream.SettableByteEntityReader;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.java.util.common.parsers.ParseException;
import org.apache.druid.segment.incremental.ParseExceptionHandler;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.realtime.appenderator.Appenderator;
import org.apache.druid.segment.realtime.appenderator.AppenderatorDriverAddResult;
import org.apache.druid.segment.realtime.appenderator.BatchAppenderatorDriver;
import org.apache.druid.segment.realtime.appenderator.SegmentsAndCommitMetadata;
import org.apache.druid.segment.realtime.appenderator.StreamAppenderatorDriver;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Index task for Kafka Share Groups.
 * Consumes from queue without partition assignment.
 */
public class KafkaShareGroupIndexTask extends AbstractTask
{
  private static final Logger log = new Logger(KafkaShareGroupIndexTask.class);
  private static final String TYPE = "index_kafka_share_group";

  private final DataSchema dataSchema;
  private final KafkaShareGroupIndexTaskTuningConfig tuningConfig;
  private final KafkaShareGroupIndexTaskIOConfig ioConfig;
  private final ObjectMapper configMapper;

  private volatile boolean stopped = false;
  private KafkaShareGroupRecordSupplier recordSupplier;

  @JsonCreator
  public KafkaShareGroupIndexTask(
      @JsonProperty("id") @Nullable String id,
      @JsonProperty("resource") @Nullable TaskResource taskResource,
      @JsonProperty("dataSchema") DataSchema dataSchema,
      @JsonProperty("tuningConfig") KafkaShareGroupIndexTaskTuningConfig tuningConfig,
      @JsonProperty("ioConfig") KafkaShareGroupIndexTaskIOConfig ioConfig,
      @JsonProperty("context") @Nullable Map<String, Object> context,
      @JacksonInject ObjectMapper configMapper
  )
  {
    super(
        id != null ? id : makeId(dataSchema.getDataSource(), ioConfig.getShareGroupId()),
        null,
        taskResource,
        dataSchema.getDataSource(),
        context
    );

    this.dataSchema = dataSchema;
    this.tuningConfig = tuningConfig;
    this.ioConfig = ioConfig;
    this.configMapper = configMapper;
  }

  private static String makeId(String dataSource, String shareGroupId)
  {
    return String.format("index_kafka_share_group_%s_%s_%s", dataSource, shareGroupId, DateTime.now());
  }

  @Override
  public String getType()
  {
    return TYPE;
  }

  @JsonProperty("dataSchema")
  public DataSchema getDataSchema()
  {
    return dataSchema;
  }

  @JsonProperty("tuningConfig")
  public KafkaShareGroupIndexTaskTuningConfig getTuningConfig()
  {
    return tuningConfig;
  }

  @JsonProperty("ioConfig")
  public KafkaShareGroupIndexTaskIOConfig getIOConfig()
  {
    return ioConfig;
  }

  @Nonnull
  @JsonIgnore
  @Override
  public Set<ResourceAction> getInputSourceResources()
  {
    return Set.of(
        new ResourceAction(new Resource(ioConfig.getTopic(), ResourceType.EXTERNAL), Action.READ)
    );
  }

  @Override
  public boolean isReady(TaskActionClient taskActionClient)
  {
    return true;
  }

  @Override
  public TaskStatus runTask(TaskToolbox toolbox) throws Exception
  {
    log.info("Starting KafkaShareGroupIndexTask for topic [%s], share group [%s]",
             ioConfig.getTopic(),
             ioConfig.getShareGroupId());

    try {
      recordSupplier = new KafkaShareGroupRecordSupplier(
          ioConfig.getConsumerProperties(),
          configMapper,
          ioConfig.getTopic(),
          ioConfig.getShareGroupId()
      );

      return run(toolbox);
    }
    catch (Exception e) {
      log.error(e, "Exception in KafkaShareGroupIndexTask");
      return TaskStatus.failure(getId(), e.getMessage());
    }
    finally {
      if (recordSupplier != null) {
        recordSupplier.close();
      }
    }
  }

  private TaskStatus run(TaskToolbox toolbox) throws Exception
  {
    final RowIngestionMeters rowIngestionMeters = toolbox.getRowIngestionMetersFactory().createRowIngestionMeters();
    final ParseExceptionHandler parseExceptionHandler = new ParseExceptionHandler(
        rowIngestionMeters,
        tuningConfig.isLogParseExceptions(),
        tuningConfig.getMaxParseExceptions(),
        tuningConfig.getMaxSavedParseExceptions()
    );

    // Setup parser for Share Group records
    final SettableByteEntityReader<KafkaRecordEntity> byteEntityReader = new SettableByteEntityReader<>(
        ioConfig.getInputFormat(),
        dataSchema.getTransformSpec().toTransformingInputRowSchema(dataSchema.getParser()),
        dataSchema.getTransformSpec(),
        toolbox.getIndexingTmpDir()
    );

    // Create segment allocator
    final TaskLockHelper taskLockHelper = new TaskLockHelper(toolbox.getTaskLockbox());
    final String sequenceName = makeSequenceName();
    final SegmentAllocatorForBatch segmentAllocator = SegmentAllocators.forLinearPartitioning(
        toolbox,
        sequenceName,
        null, // No supervisor coordination
        dataSchema,
        taskLockHelper,
        IngestionMode.APPEND,
        new DynamicPartitionsSpec(tuningConfig.getMaxRowsPerSegment(), tuningConfig.getMaxTotalRows()),
        null
    );

    // Create appenderator and driver
    final Appenderator appenderator = BatchAppenderators.newAppenderator(
        getId(),
        toolbox.getAppenderatorsManager(),
        toolbox.getSegmentGenerationMetrics(),
        toolbox,
        dataSchema,
        tuningConfig,
        rowIngestionMeters,
        parseExceptionHandler
    );

    final BatchAppenderatorDriver driver = BatchAppenderators.newDriver(appenderator, toolbox, segmentAllocator);

    try {
      driver.startJob(new ActionBasedUsedSegmentChecker(toolbox.getTaskActionClient()));

      long nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
      final DynamicPartitionsSpec partitionsSpec = new DynamicPartitionsSpec(
          tuningConfig.getMaxRowsPerSegment(),
          tuningConfig.getMaxTotalRows()
      );

      while (!stopped) {
        List<OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity>> records =
            recordSupplier.poll(ioConfig.getPollTimeout());

        if (records.isEmpty()) {
          if (System.currentTimeMillis() >= nextCheckpoint) {
            log.info("Checkpointing at empty poll");
            recordSupplier.commitSync();
            nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
          }
          continue;
        }

        // Process each record
        final List<ConsumerRecord<byte[], byte[]>> successfulRecords = new ArrayList<>();
        final List<ConsumerRecord<byte[], byte[]>> rejectedRecords = new ArrayList<>();

        for (OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity> record : records) {
          try {
            final List<InputRow> rows = parseRecord(byteEntityReader, record.getData(), parseExceptionHandler);

            for (InputRow row : rows) {
              // Get interval for this row
              final Optional<Interval> optInterval = dataSchema.getGranularitySpec().bucketInterval(
                  row.getTimestamp()
              );

              if (!optInterval.isPresent()) {
                log.warn("Row [%s] has timestamp that doesn't match any configured interval, skipping", row);
                rowIngestionMeters.incrementThrownAway();
                continue;
              }

              final Interval interval = optInterval.get();
              final AppenderatorDriverAddResult addResult = driver.add(row, sequenceName);

              if (addResult.isOk()) {
                // Check if we need to push segments
                if (addResult.isPushRequired(
                    partitionsSpec.getMaxRowsPerSegment(),
                    partitionsSpec.getMaxTotalRowsOr(DynamicPartitionsSpec.DEFAULT_MAX_TOTAL_ROWS)
                )) {
                  final SegmentsAndCommitMetadata pushed = driver.pushAllAndClear(
                      tuningConfig.getHandoffConditionTimeout()
                  );
                  log.info("Pushed [%d] segments", pushed.getSegments().size());
                }
              } else {
                throw new ISE("Failed to add row with timestamp[%s]", row.getTimestamp());
              }
            }

            // Record successfully processed
            successfulRecords.add(record.getData().getRecord());
          }
          catch (ParseException pe) {
            // Parse errors are permanent - reject the record
            log.warn(pe, "Failed to parse record, rejecting");
            parseExceptionHandler.handle(pe);
            rejectedRecords.add(record.getData().getRecord());
          }
          catch (Exception e) {
            // Transient errors - release for retry
            log.error(e, "Error processing record, releasing for retry");
            recordSupplier.acknowledge(record.getData().getRecord(), AcknowledgeType.RELEASE);
            throw e;
          }
        }

        // Acknowledge successfully processed records
        for (ConsumerRecord<byte[], byte[]> record : successfulRecords) {
          recordSupplier.acknowledge(record, AcknowledgeType.ACCEPT);
        }

        // Reject permanently failed records
        for (ConsumerRecord<byte[], byte[]> record : rejectedRecords) {
          recordSupplier.acknowledge(record, AcknowledgeType.REJECT);
        }

        // Checkpoint if needed
        if (System.currentTimeMillis() >= nextCheckpoint) {
          log.info("Checkpointing after processing [%d] records", records.size());
          recordSupplier.commitSync();
          nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
        }
      }

      // Final push of remaining segments
      final SegmentsAndCommitMetadata pushed = driver.pushAllAndClear(tuningConfig.getHandoffConditionTimeout());
      log.info("Final push: [%d] segments", pushed.getSegments().size());
      recordSupplier.commitSync();

      log.info("Task stopped gracefully");
      return TaskStatus.success(getId());
    }
    finally {
      driver.close();
    }
  }

  private List<InputRow> parseRecord(
      SettableByteEntityReader<KafkaRecordEntity> reader,
      KafkaRecordEntity entity,
      ParseExceptionHandler parseExceptionHandler
  ) throws IOException
  {
    reader.setEntity(entity);
    final List<InputRow> rows = new ArrayList<>();
    try (CloseableIterator<InputRow> iterator = reader.read()) {
      while (iterator.hasNext()) {
        rows.add(iterator.next());
      }
    }
    catch (ParseException pe) {
      parseExceptionHandler.handle(pe);
      throw pe;
    }
    return rows;
  }

  private String makeSequenceName()
  {
    return String.format("index_kafka_share_group_%s_%s", ioConfig.getTopic(), ioConfig.getShareGroupId());
  }

  @Override
  public void stopGracefully(TaskConfig taskConfig)
  {
    log.info("Stopping KafkaShareGroupIndexTask gracefully");
    stopped = true;
  }

  @Override
  public boolean supportsQueries()
  {
    return false;
  }
}
