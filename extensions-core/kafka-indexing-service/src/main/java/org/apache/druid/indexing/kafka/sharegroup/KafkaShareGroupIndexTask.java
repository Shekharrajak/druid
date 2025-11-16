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
import org.apache.druid.data.input.kafka.KafkaRecordEntity;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.config.TaskConfig;
import org.apache.druid.indexing.common.task.AbstractTask;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.common.parsers.ParseException;
import org.apache.druid.segment.incremental.ParseExceptionHandler;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
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
    final RowIngestionMeters rowIngestionMeters = toolbox.getRowIngestionMetersFactory().createRowIngestionMeters();
    final ParseExceptionHandler parseExceptionHandler = new ParseExceptionHandler(
        rowIngestionMeters,
        tuningConfig.isLogParseExceptions(),
        tuningConfig.getMaxParseExceptions(),
        tuningConfig.getMaxSavedParseExceptions()
    );

    long nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
    long recordsProcessed = 0;

    // TODO: Integrate with full segment publishing pipeline
    // For now, this demonstrates the core Share Group consumption logic

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
          // TODO: Parse and index record
          // For now, just track successful processing
          recordsProcessed++;
          successfulRecords.add(((KafkaRecordEntity) record.getData().get(0)).getRecord());
        }
        catch (ParseException pe) {
          log.warn(pe, "Failed to parse record, rejecting");
          parseExceptionHandler.handle(pe);
          rejectedRecords.add(((KafkaRecordEntity) record.getData().get(0)).getRecord());
        }
        catch (Exception e) {
          log.error(e, "Error processing record, releasing for retry");
          recordSupplier.acknowledge(((KafkaRecordEntity) record.getData().get(0)).getRecord(), AcknowledgeType.RELEASE);
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
        log.info("Checkpointing after processing [%d] records (total: [%d])", records.size(), recordsProcessed);
        recordSupplier.commitSync();
        nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
      }
    }

    log.info("Task stopped gracefully after processing [%d] records", recordsProcessed);
    return TaskStatus.success(getId());
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
