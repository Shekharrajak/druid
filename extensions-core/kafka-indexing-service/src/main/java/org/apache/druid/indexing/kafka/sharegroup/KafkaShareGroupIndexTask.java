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
import org.apache.druid.data.input.InputEntityReader;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.kafka.KafkaRecordEntity;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.appenderator.ActionBasedPublishedSegmentRetriever;
import org.apache.druid.indexing.appenderator.ActionBasedSegmentAllocator;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.LockReleaseAction;
import org.apache.druid.indexing.common.actions.SegmentAllocateAction;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.config.TaskConfig;
import org.apache.druid.indexing.common.task.AbstractTask;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.input.InputRowSchemas;
import org.apache.druid.indexing.seekablestream.SeekableStreamAppenderatorConfig;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.java.util.common.parsers.ParseException;
import org.apache.druid.segment.incremental.ParseExceptionHandler;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.realtime.SegmentGenerationMetrics;
import org.apache.druid.segment.realtime.appenderator.Appenderator;
import org.apache.druid.segment.realtime.appenderator.AppenderatorDriverAddResult;
import org.apache.druid.segment.realtime.appenderator.StreamAppenderatorDriver;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.apache.druid.timeline.partition.NumberedPartialShardSpec;
import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
  private StreamAppenderatorDriver driver;
  private InputFormat inputFormat;

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
    return StringUtils.format("index_kafka_share_group_%s_%s_%s", dataSource, shareGroupId, DateTimes.nowUtc());
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

      return runInternal(toolbox);
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

  private TaskStatus runInternal(TaskToolbox toolbox) throws Exception
  {
    log.info("Initializing Share Group ingestion for topic [%s], share group [%s]",
             ioConfig.getTopic(), ioConfig.getShareGroupId());

    final RowIngestionMeters rowIngestionMeters = toolbox.getRowIngestionMetersFactory().createRowIngestionMeters();
    final ParseExceptionHandler parseExceptionHandler = new ParseExceptionHandler(
        rowIngestionMeters,
        tuningConfig.isLogParseExceptions(),
        tuningConfig.getMaxParseExceptions(),
        tuningConfig.getMaxSavedParseExceptions()
    );

    // Initialize InputFormat
    this.inputFormat = ioConfig.getInputFormat();
    if (inputFormat == null) {
      throw new IllegalStateException("InputFormat is required for Share Group ingestion");
    }

    final SegmentGenerationMetrics metrics = new SegmentGenerationMetrics();

    // Create Appenderator for segment building
    final Appenderator appenderator = toolbox.getAppenderatorsManager().createRealtimeAppenderatorForTask(
        toolbox.getSegmentLoaderConfig(),
        getId(),
        dataSchema,
        SeekableStreamAppenderatorConfig.fromTuningConfig(
            tuningConfig.withBasePersistDirectory(toolbox.getPersistDir()),
            toolbox.getProcessingConfig()
        ),
        toolbox.getConfig(),
        metrics,
        toolbox.getSegmentPusher(),
        toolbox.getJsonMapper(),
        toolbox.getIndexIO(),
        toolbox.getIndexMerger(),
        toolbox.getQueryRunnerFactoryConglomerate(),
        toolbox.getSegmentAnnouncer(),
        toolbox.getEmitter(),
        toolbox.getQueryProcessingPool(),
        toolbox.getJoinableFactory(),
        toolbox.getCache(),
        toolbox.getCacheConfig(),
        toolbox.getCachePopulatorStats(),
        toolbox.getPolicyEnforcer(),
        rowIngestionMeters,
        parseExceptionHandler,
        toolbox.getCentralizedTableSchemaConfig(),
        interval -> {
          toolbox.getTaskActionClient().submit(new LockReleaseAction(interval));
        }
    );

    // Create driver for managing appenderator lifecycle
    this.driver = new StreamAppenderatorDriver(
        appenderator,
        new ActionBasedSegmentAllocator(
            toolbox.getTaskActionClient(),
            dataSchema,
            (schema, row, sequenceName, previousSegmentId, skipSegmentLineageCheck) -> new SegmentAllocateAction(
                schema.getDataSource(),
                row.getTimestamp(),
                schema.getGranularitySpec().getQueryGranularity(),
                schema.getGranularitySpec().getSegmentGranularity(),
                sequenceName,
                previousSegmentId,
                skipSegmentLineageCheck,
                NumberedPartialShardSpec.instance(),
                null,  // lockGranularity - use default
                null   // lockType - use default
            )
        ),
        toolbox.getSegmentHandoffNotifierFactory(),
        new ActionBasedPublishedSegmentRetriever(toolbox.getTaskActionClient()),
        toolbox.getDataSegmentKiller(),
        toolbox.getJsonMapper(),
        metrics
    );

    driver.startJob(null);

    try {
      return runIngestionLoop(toolbox, rowIngestionMeters, parseExceptionHandler);
    }
    finally {
      closeResources();
    }
  }

  private TaskStatus runIngestionLoop(
      TaskToolbox toolbox,
      RowIngestionMeters rowIngestionMeters,
      ParseExceptionHandler parseExceptionHandler
  ) throws Exception
  {
    long nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
    long nextPublish = System.currentTimeMillis() + tuningConfig.getIntermediateHandoffPeriod().toStandardDuration().getMillis();
    long recordsProcessed = 0;

    final Map<ConsumerRecord<byte[], byte[]>, InputRow> recordToRowMap = new HashMap<>();

    while (!stopped) {
      List<OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity>> records =
          recordSupplier.poll(ioConfig.getPollTimeout());

      if (records.isEmpty()) {
        // Check if we need to checkpoint or publish
        if (System.currentTimeMillis() >= nextCheckpoint) {
          log.info("Checkpointing at empty poll");
          driver.persist(null);
          recordSupplier.commitSync();
          nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
        }

        if (System.currentTimeMillis() >= nextPublish) {
          log.info("Publishing segments");
          publishSegments(toolbox);
          nextPublish = System.currentTimeMillis() + tuningConfig.getIntermediateHandoffPeriod().toStandardDuration().getMillis();
        }
        continue;
      }

      // Process batch of records
      final List<ConsumerRecord<byte[], byte[]>> successfulRecords = new ArrayList<>();
      final List<ConsumerRecord<byte[], byte[]>> rejectedRecords = new ArrayList<>();

      for (OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity> record : records) {
        try {
          // Parse record into InputRow
          final KafkaRecordEntity entity = (KafkaRecordEntity) record.getData().get(0);
          final ConsumerRecord<byte[], byte[]> consumerRecord = entity.getRecord();

          final InputRow inputRow = parseRecord(entity, parseExceptionHandler);

          if (inputRow != null) {
            // Add to appenderator
            final AppenderatorDriverAddResult addResult = driver.add(
                inputRow,
                getId(),
                null,
                false,
                true
            );

            if (addResult.isOk()) {
              recordsProcessed++;
              recordToRowMap.put(consumerRecord, inputRow);
              successfulRecords.add(consumerRecord);
              rowIngestionMeters.incrementProcessed();
            } else {
              log.warn("Failed to add row to appenderator");
              rejectedRecords.add(consumerRecord);
              rowIngestionMeters.incrementProcessedWithError();
            }
          } else {
            // Parsing returned null (filtered out or invalid)
            rejectedRecords.add(consumerRecord);
          }
        }
        catch (ParseException pe) {
          log.warn(pe, "Failed to parse record, rejecting");
          parseExceptionHandler.handle(pe);
          rejectedRecords.add(((KafkaRecordEntity) record.getData().get(0)).getRecord());
          rowIngestionMeters.incrementUnparseable();
        }
        catch (Exception e) {
          log.error(e, "Error processing record, releasing for retry");
          recordSupplier.acknowledge(
              ((KafkaRecordEntity) record.getData().get(0)).getRecord(),
              AcknowledgeType.RELEASE
          );
          throw e;
        }
      }

      // Acknowledge successfully processed records
      for (ConsumerRecord<byte[], byte[]> consumerRecord : successfulRecords) {
        recordSupplier.acknowledge(consumerRecord, AcknowledgeType.ACCEPT);
      }

      // Reject permanently failed records
      for (ConsumerRecord<byte[], byte[]> consumerRecord : rejectedRecords) {
        recordSupplier.acknowledge(consumerRecord, AcknowledgeType.REJECT);
      }

      // Periodic checkpoint
      if (System.currentTimeMillis() >= nextCheckpoint) {
        log.info("Checkpointing after processing [%d] records (total: [%d])", records.size(), recordsProcessed);
        driver.persist(null);
        recordSupplier.commitSync();
        recordToRowMap.clear();
        nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
      }

      // Periodic segment publish
      if (System.currentTimeMillis() >= nextPublish) {
        log.info("Publishing segments, records processed: [%d]", recordsProcessed);
        publishSegments(toolbox);
        recordToRowMap.clear();
        nextPublish = System.currentTimeMillis() + tuningConfig.getIntermediateHandoffPeriod().toStandardDuration().getMillis();
      }
    }

    // Final publish before shutdown
    log.info("Task stopped gracefully after processing [%d] records", recordsProcessed);
    publishSegments(toolbox);

    return TaskStatus.success(getId());
  }

  private InputRow parseRecord(KafkaRecordEntity entity, ParseExceptionHandler parseExceptionHandler)
  {
    try {
      final InputRowSchema inputRowSchema = InputRowSchemas.fromDataSchema(dataSchema);

      // Parse using InputFormat
      final InputEntityReader reader = inputFormat.createReader(
          inputRowSchema,
          entity,
          null  // temporaryDirectory
      );

      try (CloseableIterator<InputRow> iterator = reader.read()) {
        if (iterator.hasNext()) {
          return iterator.next();
        }
        return null;
      }
    }
    catch (IOException e) {
      throw new ParseException(null, e, "Failed to parse Kafka record");
    }
  }

  private void publishSegments(TaskToolbox toolbox) throws Exception
  {
    // Simplified publishing - just persist for now
    // Full publishing with handoff will be implemented in follow-up
    driver.persist(null);
    log.info("Persisted segments");
  }

  private void closeResources()
  {
    try {
      if (driver != null) {
        driver.close();
      }
    }
    catch (Exception e) {
      log.warn(e, "Failed to close driver");
    }
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
