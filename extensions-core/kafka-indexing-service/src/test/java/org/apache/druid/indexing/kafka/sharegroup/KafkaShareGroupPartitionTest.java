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

import org.junit.Assert;
import org.junit.Test;

public class KafkaShareGroupPartitionTest
{
  @Test
  public void testSyntheticPartition()
  {
    final KafkaShareGroupPartition partition1 = new KafkaShareGroupPartition("topic1", "group1");
    final KafkaShareGroupPartition partition2 = new KafkaShareGroupPartition("topic1", "group1");
    final KafkaShareGroupPartition different = new KafkaShareGroupPartition("topic2", "group1");

    // All partitions are equal (synthetic partition concept)
    Assert.assertEquals(partition1, partition2);
    Assert.assertEquals(partition1.hashCode(), partition2.hashCode());

    // But they maintain topic/group identity
    Assert.assertEquals("topic1", partition1.getTopic());
    Assert.assertEquals("group1", partition1.getShareGroupId());

    Assert.assertEquals("topic2", different.getTopic());
  }

  @Test
  public void testGetPartitionId()
  {
    final KafkaShareGroupPartition partition = new KafkaShareGroupPartition("topic", "group");

    // Always returns synthetic partition 0
    Assert.assertEquals(KafkaShareGroupPartition.SYNTHETIC_PARTITION_ID, partition.getPartitionId());
    Assert.assertEquals(0, partition.getPartitionId());
  }

  @Test
  public void testToString()
  {
    final KafkaShareGroupPartition partition = new KafkaShareGroupPartition("test-topic", "test-group");
    final String str = partition.toString();

    Assert.assertTrue(str.contains("test-topic"));
    Assert.assertTrue(str.contains("test-group"));
  }
}
