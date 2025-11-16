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
import org.apache.druid.jackson.DefaultObjectMapper;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Test;

public class KafkaShareGroupDataSourceMetadataTest
{
  private final ObjectMapper mapper = new DefaultObjectMapper();

  @Test
  public void testSerde() throws Exception
  {
    final KafkaShareGroupDataSourceMetadata metadata = new KafkaShareGroupDataSourceMetadata(
        "test-topic",
        "test-group",
        DateTime.parse("2024-01-01T00:00:00Z")
    );

    final String json = mapper.writeValueAsString(metadata);
    final KafkaShareGroupDataSourceMetadata deserialized = mapper.readValue(
        json,
        KafkaShareGroupDataSourceMetadata.class
    );

    Assert.assertEquals(metadata.getTopic(), deserialized.getTopic());
    Assert.assertEquals(metadata.getShareGroupId(), deserialized.getShareGroupId());
    Assert.assertEquals(metadata.getLastProcessedTimestamp(), deserialized.getLastProcessedTimestamp());
  }

  @Test
  public void testPlus()
  {
    final DateTime time1 = DateTime.parse("2024-01-01T00:00:00Z");
    final DateTime time2 = DateTime.parse("2024-01-02T00:00:00Z");

    final KafkaShareGroupDataSourceMetadata metadata1 = new KafkaShareGroupDataSourceMetadata(
        "test-topic",
        "test-group",
        time1
    );

    final KafkaShareGroupDataSourceMetadata metadata2 = new KafkaShareGroupDataSourceMetadata(
        "test-topic",
        "test-group",
        time2
    );

    final KafkaShareGroupDataSourceMetadata merged =
        (KafkaShareGroupDataSourceMetadata) metadata1.plus(metadata2);

    Assert.assertEquals(time2, merged.getLastProcessedTimestamp());
  }

  @Test
  public void testMatches()
  {
    final KafkaShareGroupDataSourceMetadata metadata1 = new KafkaShareGroupDataSourceMetadata(
        "test-topic",
        "test-group",
        DateTime.parse("2024-01-01T00:00:00Z")
    );

    final KafkaShareGroupDataSourceMetadata metadata2 = new KafkaShareGroupDataSourceMetadata(
        "test-topic",
        "test-group",
        DateTime.parse("2024-01-02T00:00:00Z")
    );

    final KafkaShareGroupDataSourceMetadata differentTopic = new KafkaShareGroupDataSourceMetadata(
        "other-topic",
        "test-group",
        DateTime.parse("2024-01-01T00:00:00Z")
    );

    Assert.assertTrue(metadata1.matches(metadata2));
    Assert.assertFalse(metadata1.matches(differentTopic));
  }
}
