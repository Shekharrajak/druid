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
import com.google.common.base.Optional;
import org.apache.druid.common.utils.IdUtils;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.overlord.DataSourceMetadata;
import org.apache.druid.indexing.overlord.IndexerMetadataStorageCoordinator;
import org.apache.druid.indexing.overlord.TaskMaster;
import org.apache.druid.indexing.overlord.TaskQueue;
import org.apache.druid.indexing.overlord.TaskStorage;
import org.apache.druid.indexing.overlord.supervisor.Supervisor;
import org.apache.druid.indexing.overlord.supervisor.SupervisorReport;
import org.apache.druid.indexing.overlord.supervisor.SupervisorStateManager;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.segment.incremental.RowIngestionMetersFactory;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

  // Track active tasks and their start times
  private final Map<String, DateTime> activeTasks = new HashMap<>();
  private final Map<String, Integer> taskFailureCounts = new HashMap<>();
  private static final int MAX_TASK_FAILURES = 3;

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

      // Schedule periodic task management to monitor and spawn tasks
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

      // Get all tasks for this supervisor
      final List<Task> allTasks = getActiveTasksForSupervisor();

      // Update active tasks map and check for completed/failed tasks
      updateActiveTasksState(allTasks);

      // Calculate how many tasks we need
      final int desiredReplicas = spec.getIoConfig().getReplicas();
      final int currentRunning = (int) allTasks.stream()
          .filter(t -> taskMaster.getTaskQueue().isPresent())
          .filter(t -> taskMaster.getTaskQueue().get().getTaskStatus(t.getId()).isPresent())
          .filter(t -> taskMaster.getTaskQueue().get().getTaskStatus(t.getId()).get().isRunnable())
          .count();

      log.debug("Supervisor [%s]: desired=[%d], running=[%d]", spec.getId(), desiredReplicas, currentRunning);

      // Start new tasks if needed
      if (currentRunning < desiredReplicas) {
        int tasksToStart = desiredReplicas - currentRunning;
        log.info("Starting [%d] new tasks for supervisor [%s]", tasksToStart, spec.getId());

        for (int i = 0; i < tasksToStart; i++) {
          createAndSubmitTask();
        }
      }

      // Check for tasks that have been running too long and should be replaced
      checkForExpiredTasks(allTasks);
    }
    catch (Exception e) {
      log.error(e, "Error in supervisor [%s] run cycle", spec.getId());
      stateManager.maybeSetState(SupervisorStateManager.BasicState.UNHEALTHY_SUPERVISOR);
    }
  }

  private List<Task> getActiveTasksForSupervisor()
  {
    try {
      final Optional<TaskQueue> taskQueue = taskMaster.getTaskQueue();
      if (!taskQueue.isPresent()) {
        log.warn("TaskQueue not available");
        return Collections.emptyList();
      }

      return taskStorage.getActiveTasks().stream()
          .filter(task -> task instanceof KafkaShareGroupIndexTask)
          .filter(task -> matchesSupervisor((KafkaShareGroupIndexTask) task))
          .collect(Collectors.toList());
    }
    catch (Exception e) {
      log.error(e, "Failed to get active tasks");
      return Collections.emptyList();
    }
  }

  private boolean matchesSupervisor(KafkaShareGroupIndexTask task)
  {
    return task.getDataSource().equals(spec.getDataSchema().getDataSource())
        && task.getIOConfig().getTopic().equals(spec.getIoConfig().getTopic())
        && task.getIOConfig().getShareGroupId().equals(spec.getIoConfig().getShareGroupId());
  }

  private void updateActiveTasksState(List<Task> allTasks)
  {
    final Set<String> currentTaskIds = allTasks.stream().map(Task::getId).collect(Collectors.toSet());

    // Remove tasks that are no longer active
    activeTasks.keySet().retainAll(currentTaskIds);

    // Add new tasks
    for (Task task : allTasks) {
      activeTasks.putIfAbsent(task.getId(), DateTimes.nowUtc());
    }
  }

  private void checkForExpiredTasks(List<Task> allTasks)
  {
    final long taskDurationMillis = spec.getIoConfig().getTaskDuration().toStandardDuration().getMillis();
    final DateTime now = DateTimes.nowUtc();

    for (Task task : allTasks) {
      final DateTime startTime = activeTasks.get(task.getId());
      if (startTime != null) {
        final long runningTime = now.getMillis() - startTime.getMillis();
        if (runningTime > taskDurationMillis) {
          log.info("Task [%s] has exceeded duration [%dms], will be replaced on next cycle",
                   task.getId(), runningTime);
          // Task will naturally complete based on its internal duration logic
          // Supervisor will spawn replacement on next cycle
        }
      }
    }
  }

  private void createAndSubmitTask()
  {
    try {
      final String taskId = IdUtils.getRandomIdWithPrefix("index_kafka_share_group_");

      final KafkaShareGroupIndexTaskIOConfig taskIOConfig = new KafkaShareGroupIndexTaskIOConfig(
          spec.getIoConfig().getTopic(),
          spec.getIoConfig().getShareGroupId(),
          spec.getIoConfig().getConsumerProperties(),
          spec.getIoConfig().getPollTimeout(),
          spec.getIoConfig().getCheckpointPeriod(),
          null, // useTransaction - use default
          null, // minimumMessageTime - could be derived from lateMessageRejectionStartDateTime
          null, // maximumMessageTime
          spec.getIoConfig().getInputFormat(),
          spec.getIoConfig().getConfigOverrides()
      );

      final KafkaShareGroupIndexTaskTuningConfig taskTuningConfig = spec.getTuningConfig().convertToTaskTuningConfig();

      final KafkaShareGroupIndexTask task = new KafkaShareGroupIndexTask(
          taskId,
          new TaskResource(spec.getId(), 1),
          spec.getDataSchema(),
          taskTuningConfig,
          taskIOConfig,
          spec.getContext(),
          mapper
      );

      final Optional<TaskQueue> taskQueue = taskMaster.getTaskQueue();
      if (taskQueue.isPresent()) {
        taskQueue.get().add(task);
        activeTasks.put(taskId, DateTimes.nowUtc());
        log.info("Created and submitted task [%s] for supervisor [%s]", taskId, spec.getId());
      } else {
        log.error("TaskQueue not available, cannot submit task");
      }
    }
    catch (Exception e) {
      log.error(e, "Failed to create and submit task");
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

      // Stop all running tasks if requested
      if (!stopGracefully) {
        final List<Task> activeTasks = getActiveTasksForSupervisor();
        for (Task task : activeTasks) {
          try {
            final Optional<TaskQueue> taskQueue = taskMaster.getTaskQueue();
            if (taskQueue.isPresent()) {
              log.info("Shutting down task [%s]", task.getId());
              taskQueue.get().shutdown(task.getId(), "Supervisor stopped");
            }
          }
          catch (Exception e) {
            log.error(e, "Failed to stop task [%s]", task.getId());
          }
        }
      }

      log.info("KafkaShareGroupSupervisor [%s] stopped", spec.getId());
    }
  }

  @Override
  public SupervisorReport getStatus()
  {
    return new KafkaShareGroupSupervisorReport(
        spec.getId(),
        DateTimes.nowUtc(),
        spec.getIoConfig().getTopic(),
        spec.getIoConfig().getShareGroupId(),
        spec.getIoConfig().getReplicas(),
        getActiveTasksForSupervisor().size(),
        stateManager.getSupervisorState(),
        stateManager.getExceptionEvents(),
        spec.isSuspended()
    );
  }

  @Override
  public SupervisorStateManager.State getState()
  {
    return stateManager.getSupervisorState();
  }

  @Override
  public Map<String, Map<String, Object>> getStats()
  {
    final Map<String, Object> stats = new HashMap<>();
    stats.put("activeTasks", activeTasks.size());
    stats.put("desiredReplicas", spec.getIoConfig().getReplicas());
    stats.put("topic", spec.getIoConfig().getTopic());
    stats.put("shareGroupId", spec.getIoConfig().getShareGroupId());
    stats.put("dataSource", spec.getDataSchema().getDataSource());

    return Collections.singletonMap("kafka_share_group", stats);
  }

  @Override
  public void reset(@Nullable DataSourceMetadata dataSourceMetadata)
  {
    log.info("Resetting supervisor [%s]", spec.getId());

    synchronized (this) {
      // Stop all tasks
      final List<Task> tasks = getActiveTasksForSupervisor();
      for (Task task : tasks) {
        try {
          final Optional<TaskQueue> taskQueue = taskMaster.getTaskQueue();
          if (taskQueue.isPresent()) {
            taskQueue.get().shutdown(task.getId(), "Supervisor reset");
          }
        }
        catch (Exception e) {
          log.error(e, "Failed to stop task during reset [%s]", task.getId());
        }
      }

      // Clear state
      activeTasks.clear();
      taskFailureCounts.clear();

      // Reset state manager
      stateManager.maybeSetState(SupervisorStateManager.BasicState.PENDING);

      log.info("Supervisor [%s] reset complete", spec.getId());
    }
  }

  @Override
  public Boolean isHealthy()
  {
    return stateManager.isHealthy();
  }
}
