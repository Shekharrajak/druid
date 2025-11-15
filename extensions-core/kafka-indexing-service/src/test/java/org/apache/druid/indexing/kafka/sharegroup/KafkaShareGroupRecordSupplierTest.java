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

import com.google.common.collect.ImmutableMap;
import org.apache.druid.data.input.kafka.KafkaRecordEntity;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.ShareConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.TopicPartition;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KafkaShareGroupRecordSupplierTest
{
  private static final String TOPIC = "test-topic";
  private static final String SHARE_GROUP_ID = "test-share-group";

  private ShareConsumer<byte[], byte[]> mockConsumer;
  private KafkaShareGroupRecordSupplier supplier;

  @Before
  public void setup()
  {
    mockConsumer = mock(ShareConsumer.class);
    supplier = new KafkaShareGroupRecordSupplier(mockConsumer, TOPIC, SHARE_GROUP_ID);
  }

  @Test
  public void testPollEmpty()
  {
    when(mockConsumer.poll(any(Duration.class)))
        .thenReturn(ConsumerRecords.empty());

    List<OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity>> records =
        supplier.poll(1000);

    Assert.assertTrue(records.isEmpty());
    verify(mockConsumer).poll(Duration.ofMillis(1000));
  }

  @Test
  public void testPollWithRecords()
  {
    byte[] value1 = StringUtils.toUtf8("test-data-1");
    byte[] value2 = StringUtils.toUtf8("test-data-2");

    ConsumerRecord<byte[], byte[]> record1 = new ConsumerRecord<>(
        TOPIC,
        0,
        0,
        1000L,
        null,
        null,
        0,
        0,
        null,
        value1
    );

    ConsumerRecord<byte[], byte[]> record2 = new ConsumerRecord<>(
        TOPIC,
        0,
        1,
        2000L,
        null,
        null,
        0,
        0,
        null,
        value2
    );

    Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordsMap = ImmutableMap.of(
        new TopicPartition(TOPIC, 0),
        List.of(record1, record2)
    );
    ConsumerRecords<byte[], byte[]> consumerRecords = new ConsumerRecords<>(recordsMap);

    when(mockConsumer.poll(any(Duration.class)))
        .thenReturn(consumerRecords);

    List<OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity>> records =
        supplier.poll(1000);

    Assert.assertEquals(2, records.size());

    OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity> polledRecord1 = records.get(0);
    Assert.assertEquals(TOPIC, polledRecord1.getStream());
    Assert.assertEquals(KafkaShareGroupPartition.SYNTHETIC_PARTITION, polledRecord1.getPartitionId());
    Assert.assertEquals(Long.valueOf(1000L), polledRecord1.getSequenceNumber());

    OrderedPartitionableRecord<KafkaShareGroupPartition, Long, KafkaRecordEntity> polledRecord2 = records.get(1);
    Assert.assertEquals(Long.valueOf(2000L), polledRecord2.getSequenceNumber());

    Assert.assertEquals(2, supplier.getPendingAcknowledgmentCount());
  }

  @Test
  public void testAcknowledge()
  {
    ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
        TOPIC,
        0,
        0,
        1000L,
        null,
        null,
        0,
        0,
        null,
        StringUtils.toUtf8("test")
    );

    Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordsMap = ImmutableMap.of(
        new TopicPartition(TOPIC, 0),
        Collections.singletonList(record)
    );
    ConsumerRecords<byte[], byte[]> consumerRecords = new ConsumerRecords<>(recordsMap);

    when(mockConsumer.poll(any(Duration.class)))
        .thenReturn(consumerRecords);

    supplier.poll(1000);
    Assert.assertEquals(1, supplier.getPendingAcknowledgmentCount());

    supplier.acknowledge(record, AcknowledgeType.ACCEPT);

    verify(mockConsumer).acknowledge(eq(record), eq(AcknowledgeType.ACCEPT));
    Assert.assertEquals(0, supplier.getPendingAcknowledgmentCount());
  }

  @Test
  public void testAcknowledgeDefaultAccept()
  {
    ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
        TOPIC,
        0,
        0,
        1000L,
        null,
        null,
        0,
        0,
        null,
        StringUtils.toUtf8("test")
    );

    supplier.acknowledge(record);

    verify(mockConsumer).acknowledge(eq(record), eq(AcknowledgeType.ACCEPT));
  }

  @Test
  public void testCommitSync()
  {
    Map<TopicIdPartition, Optional<KafkaException>> expectedResult = Collections.emptyMap();
    when(mockConsumer.commitSync())
        .thenReturn(expectedResult);

    Map<TopicIdPartition, Optional<KafkaException>> result = supplier.commitSync();

    Assert.assertEquals(expectedResult, result);
    verify(mockConsumer).commitSync();
  }

  @Test
  public void testCommitSyncWithTimeout()
  {
    Duration timeout = Duration.ofSeconds(10);
    Map<TopicIdPartition, Optional<KafkaException>> expectedResult = Collections.emptyMap();
    when(mockConsumer.commitSync(timeout))
        .thenReturn(expectedResult);

    Map<TopicIdPartition, Optional<KafkaException>> result = supplier.commitSync(timeout);

    Assert.assertEquals(expectedResult, result);
    verify(mockConsumer).commitSync(timeout);
  }

  @Test
  public void testCommitAsync()
  {
    supplier.commitAsync();
    verify(mockConsumer).commitAsync();
  }

  @Test
  public void testReleaseAll()
  {
    ConsumerRecord<byte[], byte[]> record1 = new ConsumerRecord<>(
        TOPIC, 0, 0, 1000L, null, null, 0, 0, null, StringUtils.toUtf8("test1")
    );
    ConsumerRecord<byte[], byte[]> record2 = new ConsumerRecord<>(
        TOPIC, 0, 1, 2000L, null, null, 0, 0, null, StringUtils.toUtf8("test2")
    );

    Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordsMap = ImmutableMap.of(
        new TopicPartition(TOPIC, 0),
        List.of(record1, record2)
    );
    ConsumerRecords<byte[], byte[]> consumerRecords = new ConsumerRecords<>(recordsMap);

    when(mockConsumer.poll(any(Duration.class)))
        .thenReturn(consumerRecords);
    when(mockConsumer.commitSync())
        .thenReturn(Collections.emptyMap());

    supplier.poll(1000);
    Assert.assertEquals(2, supplier.getPendingAcknowledgmentCount());

    supplier.releaseAll();

    verify(mockConsumer, times(2)).acknowledge(any(), eq(AcknowledgeType.RELEASE));
    verify(mockConsumer).commitSync();
    Assert.assertEquals(0, supplier.getPendingAcknowledgmentCount());
  }

  @Test
  public void testClose()
  {
    when(mockConsumer.commitSync())
        .thenReturn(Collections.emptyMap());

    supplier.close();

    verify(mockConsumer).close();
  }

  @Test
  public void testCloseWithPendingAcknowledgments()
  {
    ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
        TOPIC, 0, 0, 1000L, null, null, 0, 0, null, StringUtils.toUtf8("test")
    );

    Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordsMap = ImmutableMap.of(
        new TopicPartition(TOPIC, 0),
        Collections.singletonList(record)
    );
    ConsumerRecords<byte[], byte[]> consumerRecords = new ConsumerRecords<>(recordsMap);

    when(mockConsumer.poll(any(Duration.class)))
        .thenReturn(consumerRecords);
    when(mockConsumer.commitSync())
        .thenReturn(Collections.emptyMap());

    supplier.poll(1000);
    supplier.close();

    verify(mockConsumer).acknowledge(eq(record), eq(AcknowledgeType.RELEASE));
    verify(mockConsumer).commitSync();
    verify(mockConsumer).close();
  }

  @Test
  public void testGetTopic()
  {
    Assert.assertEquals(TOPIC, supplier.getTopic());
  }

  @Test
  public void testGetShareGroupId()
  {
    Assert.assertEquals(SHARE_GROUP_ID, supplier.getShareGroupId());
  }

  @Test
  public void testGetOldestPendingAcknowledgmentAge() throws InterruptedException
  {
    ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
        TOPIC, 0, 0, 1000L, null, null, 0, 0, null, StringUtils.toUtf8("test")
    );

    Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordsMap = ImmutableMap.of(
        new TopicPartition(TOPIC, 0),
        Collections.singletonList(record)
    );
    ConsumerRecords<byte[], byte[]> consumerRecords = new ConsumerRecords<>(recordsMap);

    when(mockConsumer.poll(any(Duration.class)))
        .thenReturn(consumerRecords);

    Assert.assertEquals(0, supplier.getOldestPendingAcknowledgmentAge());

    supplier.poll(1000);
    Thread.sleep(100);

    long age = supplier.getOldestPendingAcknowledgmentAge();
    Assert.assertTrue("Age should be at least 100ms", age >= 100);
  }

  @Test
  public void testPollAfterClose()
  {
    when(mockConsumer.commitSync())
        .thenReturn(Collections.emptyMap());

    supplier.close();

    Assert.assertThrows(IllegalStateException.class, () -> supplier.poll(1000));
  }

  @Test
  public void testAcknowledgeAfterClose()
  {
    ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
        TOPIC, 0, 0, 1000L, null, null, 0, 0, null, StringUtils.toUtf8("test")
    );

    when(mockConsumer.commitSync())
        .thenReturn(Collections.emptyMap());

    supplier.close();

    Assert.assertThrows(IllegalStateException.class, () -> supplier.acknowledge(record));
  }

  @Test
  public void testCommitSyncAfterClose()
  {
    when(mockConsumer.commitSync())
        .thenReturn(Collections.emptyMap());

    supplier.close();

    Assert.assertThrows(IllegalStateException.class, () -> supplier.commitSync());
  }
}
