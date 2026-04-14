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

package org.apache.druid.indexing.kafka;

import org.apache.druid.data.input.kafka.KafkaRecordEntity;
import org.apache.druid.data.input.kafka.KafkaTopicPartition;
import org.apache.druid.indexing.seekablestream.common.AcknowledgeType;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaShareConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Unit tests for {@link KafkaShareGroupRecordSupplier} using a mocked
 * KafkaShareConsumer.
 */
public class KafkaShareGroupRecordSupplierTest
{
  private KafkaShareConsumer<byte[], byte[]> mockConsumer;
  private KafkaShareGroupRecordSupplier supplier;

  @SuppressWarnings("unchecked")
  @Before
  public void setUp()
  {
    mockConsumer = Mockito.mock(KafkaShareConsumer.class);
    supplier = new KafkaShareGroupRecordSupplier(mockConsumer);
  }

  @After
  public void tearDown()
  {
    supplier.close();
  }

  @Test
  public void testSubscribeAndSubscription()
  {
    final Set<String> topics = Set.of("topic-a", "topic-b");
    Mockito.when(mockConsumer.subscription()).thenReturn(topics);

    supplier.subscribe(topics);
    Mockito.verify(mockConsumer).subscribe(topics);
    Assert.assertEquals(topics, supplier.subscription());
  }

  @Test
  public void testUnsubscribe()
  {
    supplier.unsubscribe();
    Mockito.verify(mockConsumer).unsubscribe();
  }

  @Test
  public void testPollWrapsRecords()
  {
    final ConsumerRecord<byte[], byte[]> record1 = new ConsumerRecord<>(
        "test-topic", 0, 100L, "key1".getBytes(), "value1".getBytes()
    );
    final ConsumerRecord<byte[], byte[]> record2 = new ConsumerRecord<>(
        "test-topic", 1, 200L, "key2".getBytes(), "value2".getBytes()
    );

    final Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordMap = new HashMap<>();
    recordMap.put(new TopicPartition("test-topic", 0), List.of(record1));
    recordMap.put(new TopicPartition("test-topic", 1), List.of(record2));
    final ConsumerRecords<byte[], byte[]> consumerRecords = new ConsumerRecords<>(recordMap);

    Mockito.when(mockConsumer.poll(Mockito.any(Duration.class))).thenReturn(consumerRecords);

    final List<OrderedPartitionableRecord<KafkaTopicPartition, Long, KafkaRecordEntity>> result =
        supplier.poll(1000);

    Assert.assertEquals(2, result.size());

    final OrderedPartitionableRecord<KafkaTopicPartition, Long, KafkaRecordEntity> polled1 =
        result.stream().filter(r -> r.getSequenceNumber() == 100L).findFirst().orElse(null);
    Assert.assertNotNull(polled1);
    Assert.assertEquals("test-topic", polled1.getStream());
    Assert.assertEquals(0, polled1.getPartitionId().partition());
    Assert.assertNotNull(polled1.getData());
    Assert.assertEquals(1, polled1.getData().size());
  }

  @Test
  public void testPollReturnsEmptyOnTimeout()
  {
    Mockito.when(mockConsumer.poll(Mockito.any(Duration.class))).thenReturn(ConsumerRecords.empty());
    final List<OrderedPartitionableRecord<KafkaTopicPartition, Long, KafkaRecordEntity>> result =
        supplier.poll(100);
    Assert.assertTrue(result.isEmpty());
  }

  @Test
  public void testAcknowledgeDefaultAccept()
  {
    final KafkaTopicPartition partition = new KafkaTopicPartition(true, "test-topic", 0);
    supplier.acknowledge(partition, 42L);

    Mockito.verify(mockConsumer).acknowledge(
        Mockito.argThat(r -> r.offset() == 42L && r.topic().equals("test-topic") && r.partition() == 0),
        Mockito.eq(org.apache.kafka.clients.consumer.AcknowledgeType.ACCEPT)
    );
  }

  @Test
  public void testAcknowledgeWithRelease()
  {
    final KafkaTopicPartition partition = new KafkaTopicPartition(true, "test-topic", 0);
    supplier.acknowledge(partition, 10L, AcknowledgeType.RELEASE);

    Mockito.verify(mockConsumer).acknowledge(
        Mockito.argThat(r -> r.offset() == 10L),
        Mockito.eq(org.apache.kafka.clients.consumer.AcknowledgeType.RELEASE)
    );
  }

  @Test
  public void testAcknowledgeWithReject()
  {
    final KafkaTopicPartition partition = new KafkaTopicPartition(true, "test-topic", 0);
    supplier.acknowledge(partition, 10L, AcknowledgeType.REJECT);

    Mockito.verify(mockConsumer).acknowledge(
        Mockito.argThat(r -> r.offset() == 10L),
        Mockito.eq(org.apache.kafka.clients.consumer.AcknowledgeType.REJECT)
    );
  }

  @Test
  public void testAcknowledgeBatch()
  {
    final KafkaTopicPartition p0 = new KafkaTopicPartition(true, "test-topic", 0);
    final KafkaTopicPartition p1 = new KafkaTopicPartition(true, "test-topic", 1);

    final Map<KafkaTopicPartition, java.util.Collection<Long>> offsets = new HashMap<>();
    offsets.put(p0, Arrays.asList(1L, 2L, 3L));
    offsets.put(p1, Arrays.asList(10L, 11L));

    supplier.acknowledge(offsets, AcknowledgeType.ACCEPT);

    // 3 + 2 = 5 individual acknowledge calls
    Mockito.verify(mockConsumer, Mockito.times(5)).acknowledge(
        Mockito.any(ConsumerRecord.class),
        Mockito.eq(org.apache.kafka.clients.consumer.AcknowledgeType.ACCEPT)
    );
  }

  @Test
  public void testCommitSync()
  {
    final TopicIdPartition tip = new TopicIdPartition(Uuid.randomUuid(), new TopicPartition("test-topic", 0));
    final Map<TopicIdPartition, Optional<KafkaException>> kafkaResult = new HashMap<>();
    kafkaResult.put(tip, Optional.empty());

    Mockito.when(mockConsumer.commitSync()).thenReturn(kafkaResult);

    final Map<KafkaTopicPartition, Optional<Exception>> result = supplier.commitSync();
    Assert.assertEquals(1, result.size());

    final Map.Entry<KafkaTopicPartition, Optional<Exception>> entry = result.entrySet().iterator().next();
    Assert.assertEquals("test-topic", entry.getKey().topic().orElse(null));
    Assert.assertEquals(0, entry.getKey().partition());
    Assert.assertFalse(entry.getValue().isPresent());
  }

  @Test
  public void testCommitSyncWithError()
  {
    final TopicIdPartition tip = new TopicIdPartition(Uuid.randomUuid(), new TopicPartition("test-topic", 0));
    final KafkaException error = new KafkaException("commit failed");
    final Map<TopicIdPartition, Optional<KafkaException>> kafkaResult = new HashMap<>();
    kafkaResult.put(tip, Optional.of(error));

    Mockito.when(mockConsumer.commitSync()).thenReturn(kafkaResult);

    final Map<KafkaTopicPartition, Optional<Exception>> result = supplier.commitSync();
    final Optional<Exception> maybeError = result.values().iterator().next();
    Assert.assertTrue(maybeError.isPresent());
    Assert.assertEquals("commit failed", maybeError.get().getMessage());
  }

  @Test
  public void testCloseIsIdempotent()
  {
    supplier.close();
    supplier.close();
    Mockito.verify(mockConsumer, Mockito.times(1)).close();
  }
}
