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
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.task.AbstractTask;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.joda.time.DateTime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
    long nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();

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

      // TODO: Process records, add to incremental index
      // TODO: Persist to segments when threshold reached
      // TODO: Acknowledge processed records

      for (OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity> record : records) {
        // Process record (placeholder - needs full implementation)
        log.debug("Processing record at timestamp [%d]", record.getSequenceNumber());
      }

      if (System.currentTimeMillis() >= nextCheckpoint) {
        log.info("Checkpointing after processing [%d] records", records.size());
        recordSupplier.commitSync();
        nextCheckpoint = System.currentTimeMillis() + ioConfig.getCheckpointPeriod().toStandardDuration().getMillis();
      }
    }

    log.info("Task stopped gracefully");
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
