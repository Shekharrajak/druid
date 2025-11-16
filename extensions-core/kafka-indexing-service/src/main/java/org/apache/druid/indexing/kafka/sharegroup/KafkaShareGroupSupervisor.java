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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.indexing.overlord.IndexerMetadataStorageCoordinator;
import org.apache.druid.indexing.overlord.TaskMaster;
import org.apache.druid.indexing.overlord.TaskStorage;
import org.apache.druid.indexing.overlord.supervisor.Supervisor;
import org.apache.druid.indexing.overlord.supervisor.SupervisorReport;
import org.apache.druid.indexing.overlord.supervisor.SupervisorStateManager;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.segment.incremental.RowIngestionMetersFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Supervisor for Kafka Share Group ingestion.
 * Manages lifecycle of Share Group tasks without partition assignment.
 */
public class KafkaShareGroupSupervisor implements Supervisor
{
  private static final Logger log = new Logger(KafkaShareGroupSupervisor.class);

  private final TaskStorage taskStorage;
  private final TaskMaster taskMaster;
  private final IndexerMetadataStorageCoordinator indexerMetadataStorageCoordinator;
  private final ObjectMapper mapper;
  private final KafkaShareGroupSupervisorSpec spec;
  private final RowIngestionMetersFactory rowIngestionMetersFactory;

  private final SupervisorStateManager stateManager;
  private final ScheduledExecutorService exec;
  private volatile boolean started = false;
  private volatile boolean stopped = false;

  public KafkaShareGroupSupervisor(
      TaskStorage taskStorage,
      TaskMaster taskMaster,
      IndexerMetadataStorageCoordinator indexerMetadataStorageCoordinator,
      ObjectMapper mapper,
      KafkaShareGroupSupervisorSpec spec,
      RowIngestionMetersFactory rowIngestionMetersFactory
  )
  {
    this.taskStorage = taskStorage;
    this.taskMaster = taskMaster;
    this.indexerMetadataStorageCoordinator = indexerMetadataStorageCoordinator;
    this.mapper = mapper;
    this.spec = spec;
    this.rowIngestionMetersFactory = rowIngestionMetersFactory;

    this.stateManager = new SupervisorStateManager(null, spec.isSuspended());
    this.exec = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "KafkaShareGroupSupervisor-" + spec.getId()));
  }

  @Override
  public void start()
  {
    synchronized (this) {
      if (started) {
        log.warn("Supervisor already started");
        return;
      }

      log.info("Starting KafkaShareGroupSupervisor [%s] for topic [%s], share group [%s]",
               spec.getId(),
               spec.getIoConfig().getTopic(),
               spec.getIoConfig().getShareGroupId());

      stateManager.maybeSetState(SupervisorStateManager.BasicState.PENDING);
      started = true;

      // TODO: Schedule periodic task management
      exec.scheduleAtFixedRate(
          this::runInternal,
          0,
          spec.getIoConfig().getPeriod().toStandardDuration().getMillis(),
          TimeUnit.MILLISECONDS
      );
    }
  }

  private void runInternal()
  {
    try {
      if (stopped) {
        return;
      }

      stateManager.maybeSetState(SupervisorStateManager.BasicState.RUNNING);

      // TODO: Implement task management logic:
      // 1. Check number of running tasks
      // 2. Start new tasks if needed (based on replicas config)
      // 3. Monitor task health
      // 4. Handle task completion (duration-based)
      // 5. Restart failed tasks

      log.debug("Supervisor [%s] tick", spec.getId());
    }
    catch (Exception e) {
      log.error(e, "Error in supervisor [%s] run cycle", spec.getId());
      stateManager.maybeSetState(SupervisorStateManager.BasicState.UNHEALTHY_SUPERVISOR);
    }
  }

  @Override
  public void stop(boolean stopGracefully)
  {
    synchronized (this) {
      if (stopped) {
        return;
      }

      log.info("Stopping KafkaShareGroupSupervisor [%s], graceful: [%b]", spec.getId(), stopGracefully);

      stopped = true;
      stateManager.maybeSetState(SupervisorStateManager.BasicState.STOPPING);

      if (exec != null) {
        exec.shutdownNow();
        try {
          if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("Executor did not terminate in 10 seconds");
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn(e, "Interrupted while waiting for executor to terminate");
        }
      }

      // TODO: Stop all running tasks if stopGracefully is false

      stateManager.maybeSetState(SupervisorStateManager.BasicState.STOPPED);
      log.info("KafkaShareGroupSupervisor [%s] stopped", spec.getId());
    }
  }

  @Override
  public SupervisorReport getStatus()
  {
    // TODO: Implement detailed status report
    return new SupervisorReport(
        spec.getId(),
        null,
        Collections.emptyMap()
    )
    {
      @Override
      public Object getPayload()
      {
        return Collections.emptyMap();
      }
    };
  }

  @Override
  public Map<String, Map<String, Object>> getStats()
  {
    // TODO: Implement metrics collection
    return Collections.emptyMap();
  }

  @Override
  public Boolean isHealthy()
  {
    return stateManager.isHealthy();
  }

  @Override
  public void reset(Map<String, Object> resetOptions)
  {
    log.info("Resetting supervisor [%s]", spec.getId());
    // TODO: Implement reset logic (stop tasks, clear state, restart)
  }

  @Override
  public void checkpoint(int taskGroupId, Map<String, Object> checkpointMetadata)
  {
    log.info("Checkpoint requested for task group [%d]", taskGroupId);
    // TODO: Implement checkpoint coordination with tasks
  }

  @Override
  public List<String> possiblyRegisterListener(Map<String, Object> checkpointMetadata)
  {
    // No listeners needed for Share Groups
    return Collections.emptyList();
  }
}
