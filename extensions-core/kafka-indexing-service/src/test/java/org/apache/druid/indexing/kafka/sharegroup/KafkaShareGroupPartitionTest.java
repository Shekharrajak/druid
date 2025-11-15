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
import org.apache.druid.segment.TestHelper;
import org.junit.Assert;
import org.junit.Test;

public class KafkaShareGroupPartitionTest
{
  private static final ObjectMapper OBJECT_MAPPER = TestHelper.makeJsonMapper();

  @Test
  public void testSyntheticPartitionId()
  {
    KafkaShareGroupPartition partition = KafkaShareGroupPartition.SYNTHETIC_PARTITION;
    Assert.assertEquals(0, partition.partition());
    Assert.assertTrue(partition.isSynthetic());
  }

  @Test
  public void testWithTopic()
  {
    KafkaShareGroupPartition partition = new KafkaShareGroupPartition("test-topic");
    Assert.assertEquals(0, partition.partition());
    Assert.assertEquals("test-topic", partition.topic());
    Assert.assertFalse(partition.isSynthetic());
  }

  @Test
  public void testEquality()
  {
    KafkaShareGroupPartition p1 = new KafkaShareGroupPartition("topic1");
    KafkaShareGroupPartition p2 = new KafkaShareGroupPartition("topic2");
    KafkaShareGroupPartition p3 = KafkaShareGroupPartition.SYNTHETIC_PARTITION;

    // All partitions are equal (single queue semantics)
    Assert.assertEquals(p1, p2);
    Assert.assertEquals(p1, p3);
    Assert.assertEquals(p2, p3);
  }

  @Test
  public void testHashCode()
  {
    KafkaShareGroupPartition p1 = new KafkaShareGroupPartition("topic1");
    KafkaShareGroupPartition p2 = new KafkaShareGroupPartition("topic2");

    // Same hash code since all use SYNTHETIC_PARTITION_ID
    Assert.assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testToString()
  {
    KafkaShareGroupPartition synthetic = KafkaShareGroupPartition.SYNTHETIC_PARTITION;
    Assert.assertEquals("KafkaShareGroupPartition{SYNTHETIC}", synthetic.toString());

    KafkaShareGroupPartition withTopic = new KafkaShareGroupPartition("test-topic");
    Assert.assertTrue(withTopic.toString().contains("test-topic"));
    Assert.assertTrue(withTopic.toString().contains("partition=0"));
    Assert.assertTrue(withTopic.toString().contains("synthetic"));
  }

  @Test
  public void testJsonSerialization() throws Exception
  {
    KafkaShareGroupPartition partition = new KafkaShareGroupPartition("test-topic");
    String json = OBJECT_MAPPER.writeValueAsString(partition);
    KafkaShareGroupPartition deserialized = OBJECT_MAPPER.readValue(json, KafkaShareGroupPartition.class);

    Assert.assertEquals(partition.topic(), deserialized.topic());
    Assert.assertEquals(partition.partition(), deserialized.partition());
  }
}
