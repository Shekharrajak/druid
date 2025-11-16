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
import com.google.common.base.Preconditions;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.indexing.overlord.IndexerMetadataStorageCoordinator;
import org.apache.druid.indexing.overlord.TaskMaster;
import org.apache.druid.indexing.overlord.TaskStorage;
import org.apache.druid.indexing.overlord.supervisor.Supervisor;
import org.apache.druid.indexing.overlord.supervisor.SupervisorSpec;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.segment.incremental.RowIngestionMetersFactory;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supervisor spec for Kafka Share Groups.
 */
public class KafkaShareGroupSupervisorSpec implements SupervisorSpec
{
  private static final String TYPE = "kafka_share_group";

  private final String id;
  private final DataSchema dataSchema;
  private final KafkaShareGroupSupervisorTuningConfig tuningConfig;
  private final KafkaShareGroupSupervisorIOConfig ioConfig;
  private final Map<String, Object> context;
  private final boolean suspended;

  private final TaskStorage taskStorage;
  private final TaskMaster taskMaster;
  private final IndexerMetadataStorageCoordinator indexerMetadataStorageCoordinator;
  private final ObjectMapper mapper;
  private final ServiceEmitter emitter;
  private final RowIngestionMetersFactory rowIngestionMetersFactory;

  @JsonCreator
  public KafkaShareGroupSupervisorSpec(
      @JsonProperty("id") @Nullable String id,
      @JsonProperty("dataSchema") DataSchema dataSchema,
      @JsonProperty("tuningConfig") @Nullable KafkaShareGroupSupervisorTuningConfig tuningConfig,
      @JsonProperty("ioConfig") KafkaShareGroupSupervisorIOConfig ioConfig,
      @JsonProperty("context") @Nullable Map<String, Object> context,
      @JsonProperty("suspended") @Nullable Boolean suspended,
      @JacksonInject TaskStorage taskStorage,
      @JacksonInject TaskMaster taskMaster,
      @JacksonInject IndexerMetadataStorageCoordinator indexerMetadataStorageCoordinator,
      @JacksonInject @Json ObjectMapper mapper,
      @JacksonInject ServiceEmitter emitter,
      @JacksonInject RowIngestionMetersFactory rowIngestionMetersFactory
  )
  {
    this.id = id != null ? id : generateSupervisorId(dataSchema.getDataSource(), ioConfig.getShareGroupId());
    this.dataSchema = Preconditions.checkNotNull(dataSchema, "dataSchema");
    this.tuningConfig = tuningConfig != null ? tuningConfig : KafkaShareGroupSupervisorTuningConfig.defaultConfig();
    this.ioConfig = Preconditions.checkNotNull(ioConfig, "ioConfig");
    this.context = context;
    this.suspended = suspended != null ? suspended : false;
    this.taskStorage = taskStorage;
    this.taskMaster = taskMaster;
    this.indexerMetadataStorageCoordinator = indexerMetadataStorageCoordinator;
    this.mapper = mapper;
    this.emitter = emitter;
    this.rowIngestionMetersFactory = rowIngestionMetersFactory;
  }

  private static String generateSupervisorId(String dataSource, String shareGroupId)
  {
    return String.format("%s-%s-%s", TYPE, dataSource, shareGroupId);
  }

  @Override
  @JsonProperty
  public String getId()
  {
    return id;
  }

  @Override
  public Supervisor createSupervisor()
  {
    return new KafkaShareGroupSupervisor(
        taskStorage,
        taskMaster,
        indexerMetadataStorageCoordinator,
        mapper,
        this,
        rowIngestionMetersFactory
    );
  }

  @Override
  @JsonProperty
  public String getType()
  {
    return TYPE;
  }

  @Override
  @JsonProperty
  public String getSource()
  {
    return ioConfig.getTopic();
  }

  @JsonProperty
  public DataSchema getDataSchema()
  {
    return dataSchema;
  }

  @JsonProperty
  public KafkaShareGroupSupervisorTuningConfig getTuningConfig()
  {
    return tuningConfig;
  }

  @JsonProperty
  public KafkaShareGroupSupervisorIOConfig getIoConfig()
  {
    return ioConfig;
  }

  @Override
  @JsonProperty
  @Nullable
  public Map<String, Object> getContext()
  {
    return context;
  }

  @Override
  @JsonProperty
  public boolean isSuspended()
  {
    return suspended;
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
  public SupervisorSpec createSuspendedSpec()
  {
    return new KafkaShareGroupSupervisorSpec(
        id,
        dataSchema,
        tuningConfig,
        ioConfig,
        context,
        true,
        taskStorage,
        taskMaster,
        indexerMetadataStorageCoordinator,
        mapper,
        emitter,
        rowIngestionMetersFactory
    );
  }

  @Override
  public SupervisorSpec createRunningSpec()
  {
    return new KafkaShareGroupSupervisorSpec(
        id,
        dataSchema,
        tuningConfig,
        ioConfig,
        context,
        false,
        taskStorage,
        taskMaster,
        indexerMetadataStorageCoordinator,
        mapper,
        emitter,
        rowIngestionMetersFactory
    );
  }

  @Override
  public List<String> getDataSources()
  {
    return List.of(dataSchema.getDataSource());
  }

  @Override
  public String toString()
  {
    return "KafkaShareGroupSupervisorSpec{" +
           "id='" + id + '\'' +
           ", dataSchema=" + dataSchema +
           ", tuningConfig=" + tuningConfig +
           ", ioConfig=" + ioConfig +
           ", context=" + context +
           ", suspended=" + suspended +
           '}';
  }
}
