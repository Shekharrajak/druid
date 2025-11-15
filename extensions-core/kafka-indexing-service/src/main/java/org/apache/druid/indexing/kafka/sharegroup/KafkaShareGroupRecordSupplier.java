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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import org.apache.druid.common.utils.IdUtils;
import org.apache.druid.data.input.kafka.KafkaRecordEntity;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.indexing.seekablestream.common.StreamException;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.ShareConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RecordSupplier for Kafka Share Groups (KIP-932) with queue-based consumption.
 * No partitions, no seeking - just poll, acknowledge (ACCEPT/RELEASE/REJECT), and commit.
 */
public class KafkaShareGroupRecordSupplier
{
  private static final Logger log = new Logger(KafkaShareGroupRecordSupplier.class);

  private final ShareConsumer<byte[], byte[]> shareConsumer;
  private final String topic;
  private final String shareGroupId;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final Map<ConsumerRecord<byte[], byte[]>, Long> pendingAcknowledgments = new ConcurrentHashMap<>();
  private static final Duration DEFAULT_ACQUISITION_LOCK_TIMEOUT = Duration.ofSeconds(30);

  public KafkaShareGroupRecordSupplier(
      Map<String, Object> consumerProperties,
      ObjectMapper sortingMapper,
      String topic,
      String shareGroupId
  )
  {
    this(getShareConsumer(sortingMapper, consumerProperties, shareGroupId), topic, shareGroupId);
  }

  @VisibleForTesting
  KafkaShareGroupRecordSupplier(
      ShareConsumer<byte[], byte[]> shareConsumer,
      String topic,
      String shareGroupId
  )
  {
    this.shareConsumer = shareConsumer;
    this.topic = topic;
    this.shareGroupId = shareGroupId;
    shareConsumer.subscribe(Collections.singletonList(topic));

    log.info(
        "Initialized KafkaShareGroupRecordSupplier for topic [%s] with share group [%s]",
        topic,
        shareGroupId
    );
  }

  /**
   * Poll records from share group. Records auto-release if not acknowledged within lock timeout.
   */
  @Nonnull
  public List<OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity>> poll(long timeoutMs)
  {
    if (closed.get()) {
      throw new IllegalStateException("RecordSupplier has been closed");
    }

    ConsumerRecords<byte[], byte[]> records = wrapExceptions(
        () -> shareConsumer.poll(Duration.ofMillis(timeoutMs))
    );

    List<OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity>> result = new ArrayList<>();

    for (ConsumerRecord<byte[], byte[]> record : records) {
      pendingAcknowledgments.put(record, System.currentTimeMillis());

      result.add(new OrderedPartitionableRecord<>(
          topic,
          KafkaShareGroupPartition.SYNTHETIC_PARTITION,
          record.timestamp(),
          record.value() == null ? null : ImmutableList.of(new KafkaRecordEntity(record)),
          record.timestamp()
      ));
    }

    long oldestPendingAge = getOldestPendingAcknowledgmentAge();
    if (oldestPendingAge > DEFAULT_ACQUISITION_LOCK_TIMEOUT.toMillis() * 0.8) {
      log.warn(
          "Oldest pending acknowledgment is %d ms old, approaching acquisition lock timeout of %d ms. " +
          "Consider checkpointing more frequently to avoid automatic release.",
          oldestPendingAge,
          DEFAULT_ACQUISITION_LOCK_TIMEOUT.toMillis()
      );
    }

    return result;
  }

  /**
   * Acknowledge record: ACCEPT (success), RELEASE (redelivery), REJECT (dead letter).
   */
  public void acknowledge(ConsumerRecord<byte[], byte[]> record, AcknowledgeType acknowledgeType)
  {
    if (closed.get()) {
      throw new IllegalStateException("RecordSupplier has been closed");
    }

    wrapExceptions(() -> {
      shareConsumer.acknowledge(record, acknowledgeType);
      pendingAcknowledgments.remove(record);
      return null;
    });

    log.debug("Acknowledged record at timestamp [%d] with type [%s]", record.timestamp(), acknowledgeType);
  }

  public void acknowledge(ConsumerRecord<byte[], byte[]> record)
  {
    acknowledge(record, AcknowledgeType.ACCEPT);
  }

  /**
   * Commit acknowledgments synchronously and release locks.
   */
  public Map<TopicIdPartition, Optional<KafkaException>> commitSync()
  {
    if (closed.get()) {
      throw new IllegalStateException("RecordSupplier has been closed");
    }

    Map<TopicIdPartition, Optional<KafkaException>> result = wrapExceptions(
        () -> shareConsumer.commitSync()
    );

    log.info("Committed acknowledgments for share group [%s]", shareGroupId);

    result.forEach((partition, error) -> {
      if (error.isPresent()) {
        log.error(
            "Failed to commit acknowledgments for partition [%s]: %s",
            partition,
            error.get().getMessage()
        );
      }
    });

    return result;
  }

  public Map<TopicIdPartition, Optional<KafkaException>> commitSync(Duration timeout)
  {
    if (closed.get()) {
      throw new IllegalStateException("RecordSupplier has been closed");
    }

    return wrapExceptions(() -> shareConsumer.commitSync(timeout));
  }

  public void commitAsync()
  {
    if (closed.get()) {
      throw new IllegalStateException("RecordSupplier has been closed");
    }

    wrapExceptions(() -> {
      shareConsumer.commitAsync();
      return null;
    });

    log.debug("Initiated async commit for share group [%s]", shareGroupId);
  }

  /**
   * Release all pending acknowledgments (for graceful shutdown).
   */
  public void releaseAll()
  {
    List<ConsumerRecord<byte[], byte[]>> toRelease = new ArrayList<>(pendingAcknowledgments.keySet());

    for (ConsumerRecord<byte[], byte[]> record : toRelease) {
      try {
        acknowledge(record, AcknowledgeType.RELEASE);
      } catch (Exception e) {
        log.error(e, "Failed to release record at timestamp [%d]", record.timestamp());
      }
    }

    if (!toRelease.isEmpty()) {
      try {
        commitSync();
        log.info("Released %d pending acknowledgments", toRelease.size());
      } catch (Exception e) {
        log.error(e, "Failed to commit release acknowledgments");
      }
    }
  }

  public int getPendingAcknowledgmentCount()
  {
    return pendingAcknowledgments.size();
  }

  public long getOldestPendingAcknowledgmentAge()
  {
    if (pendingAcknowledgments.isEmpty()) {
      return 0;
    }

    long now = System.currentTimeMillis();
    long oldest = pendingAcknowledgments.values().stream()
        .mapToLong(timestamp -> now - timestamp)
        .max()
        .orElse(0);

    return oldest;
  }

  public String getTopic()
  {
    return topic;
  }

  public String getShareGroupId()
  {
    return shareGroupId;
  }

  public void close()
  {
    if (closed.compareAndSet(false, true)) {
      log.info("Closing KafkaShareGroupRecordSupplier for topic [%s]", topic);
      releaseAll();
      shareConsumer.close();
    }
  }

  private static ShareConsumer<byte[], byte[]> getShareConsumer(
      ObjectMapper sortingMapper,
      Map<String, Object> consumerProperties,
      String shareGroupId
  )
  {
    final Properties props = new Properties();

    for (Map.Entry<String, Object> entry : consumerProperties.entrySet()) {
      props.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
    }

    props.putIfAbsent("group.id", shareGroupId);
    props.putIfAbsent("group.protocol", "share");
    props.putIfAbsent("isolation.level", "read_committed");
    props.putIfAbsent("key.deserializer", ByteArrayDeserializer.class.getName());
    props.putIfAbsent("value.deserializer", ByteArrayDeserializer.class.getName());

    ClassLoader currCtxCl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(KafkaShareGroupRecordSupplier.class.getClassLoader());

      Deserializer<byte[]> keyDeserializer = new ByteArrayDeserializer();
      Deserializer<byte[]> valueDeserializer = new ByteArrayDeserializer();

      return new org.apache.kafka.clients.consumer.KafkaShareConsumer<>(
          props,
          keyDeserializer,
          valueDeserializer
      );
    }
    finally {
      Thread.currentThread().setContextClassLoader(currCtxCl);
    }
  }

  private static <T> T wrapExceptions(java.util.concurrent.Callable<T> callable)
  {
    try {
      return callable.call();
    }
    catch (Exception e) {
      throw new StreamException(e);
    }
  }
}
