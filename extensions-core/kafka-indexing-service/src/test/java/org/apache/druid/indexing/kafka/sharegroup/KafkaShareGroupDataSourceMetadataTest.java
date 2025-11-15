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
import org.apache.druid.indexing.overlord.DataSourceMetadata;
import org.apache.druid.segment.TestHelper;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Test;

public class KafkaShareGroupDataSourceMetadataTest
{
  private static final ObjectMapper OBJECT_MAPPER = TestHelper.makeJsonMapper();

  private static final KafkaShareGroupDataSourceMetadata META0 =
      new KafkaShareGroupDataSourceMetadata("topic1", "group1", null);
  private static final KafkaShareGroupDataSourceMetadata META1 =
      new KafkaShareGroupDataSourceMetadata("topic1", "group1", new DateTime("2024-01-01T00:00:00Z"));
  private static final KafkaShareGroupDataSourceMetadata META2 =
      new KafkaShareGroupDataSourceMetadata("topic1", "group1", new DateTime("2024-01-02T00:00:00Z"));
  private static final KafkaShareGroupDataSourceMetadata META3 =
      new KafkaShareGroupDataSourceMetadata("topic2", "group1", new DateTime("2024-01-01T00:00:00Z"));
  private static final KafkaShareGroupDataSourceMetadata META4 =
      new KafkaShareGroupDataSourceMetadata("topic1", "group2", new DateTime("2024-01-01T00:00:00Z"));

  @Test
  public void testMatches()
  {
    Assert.assertTrue(META0.matches(META0));
    Assert.assertTrue(META0.matches(META1));
    Assert.assertTrue(META0.matches(META2));
    Assert.assertFalse(META0.matches(META3)); // Different topic
    Assert.assertFalse(META0.matches(META4)); // Different share group

    Assert.assertTrue(META1.matches(META0));
    Assert.assertTrue(META1.matches(META1));
    Assert.assertTrue(META1.matches(META2));
    Assert.assertFalse(META1.matches(META3));
    Assert.assertFalse(META1.matches(META4));

    Assert.assertTrue(META2.matches(META1));
  }

  @Test
  public void testPlus()
  {
    // Null timestamp + timestamp = timestamp
    KafkaShareGroupDataSourceMetadata result1 = (KafkaShareGroupDataSourceMetadata) META0.plus(META1);
    Assert.assertEquals("topic1", result1.getTopic());
    Assert.assertEquals("group1", result1.getShareGroupId());
    Assert.assertEquals(new DateTime("2024-01-01T00:00:00Z"), result1.getLastProcessedTimestamp());

    // Timestamp + null = timestamp
    KafkaShareGroupDataSourceMetadata result2 = (KafkaShareGroupDataSourceMetadata) META1.plus(META0);
    Assert.assertEquals(new DateTime("2024-01-01T00:00:00Z"), result2.getLastProcessedTimestamp());

    // Take max timestamp
    KafkaShareGroupDataSourceMetadata result3 = (KafkaShareGroupDataSourceMetadata) META1.plus(META2);
    Assert.assertEquals(new DateTime("2024-01-02T00:00:00Z"), result3.getLastProcessedTimestamp());

    KafkaShareGroupDataSourceMetadata result4 = (KafkaShareGroupDataSourceMetadata) META2.plus(META1);
    Assert.assertEquals(new DateTime("2024-01-02T00:00:00Z"), result4.getLastProcessedTimestamp());
  }

  @Test
  public void testPlusIncompatible()
  {
    // Different topic
    Assert.assertThrows(IllegalArgumentException.class, () -> META1.plus(META3));

    // Different share group
    Assert.assertThrows(IllegalArgumentException.class, () -> META1.plus(META4));
  }

  @Test
  public void testMinus()
  {
    // Minus always returns unchanged for Share Groups
    KafkaShareGroupDataSourceMetadata result1 = (KafkaShareGroupDataSourceMetadata) META1.minus(META2);
    Assert.assertEquals(META1, result1);

    KafkaShareGroupDataSourceMetadata result2 = (KafkaShareGroupDataSourceMetadata) META2.minus(META1);
    Assert.assertEquals(META2, result2);
  }

  @Test
  public void testWithLastProcessedTimestamp()
  {
    DateTime newTimestamp = new DateTime("2024-06-15T12:00:00Z");
    KafkaShareGroupDataSourceMetadata updated = META0.withLastProcessedTimestamp(newTimestamp);

    Assert.assertEquals("topic1", updated.getTopic());
    Assert.assertEquals("group1", updated.getShareGroupId());
    Assert.assertEquals(newTimestamp, updated.getLastProcessedTimestamp());
  }

  @Test
  public void testEquality()
  {
    KafkaShareGroupDataSourceMetadata same1 = new KafkaShareGroupDataSourceMetadata(
        "topic1",
        "group1",
        new DateTime("2024-01-01T00:00:00Z")
    );

    Assert.assertEquals(META1, same1);
    Assert.assertNotEquals(META1, META2); // Different timestamp
    Assert.assertNotEquals(META1, META3); // Different topic
    Assert.assertNotEquals(META1, META4); // Different share group
  }

  @Test
  public void testHashCode()
  {
    KafkaShareGroupDataSourceMetadata same1 = new KafkaShareGroupDataSourceMetadata(
        "topic1",
        "group1",
        new DateTime("2024-01-01T00:00:00Z")
    );

    Assert.assertEquals(META1.hashCode(), same1.hashCode());
  }

  @Test
  public void testToString()
  {
    String str = META1.toString();
    Assert.assertTrue(str.contains("topic1"));
    Assert.assertTrue(str.contains("group1"));
    Assert.assertTrue(str.contains("2024-01-01"));
  }

  @Test
  public void testJsonSerialization() throws Exception
  {
    String json = OBJECT_MAPPER.writeValueAsString(META1);
    DataSourceMetadata deserialized = OBJECT_MAPPER.readValue(json, DataSourceMetadata.class);

    Assert.assertTrue(deserialized instanceof KafkaShareGroupDataSourceMetadata);
    KafkaShareGroupDataSourceMetadata meta = (KafkaShareGroupDataSourceMetadata) deserialized;
    Assert.assertEquals(META1.getTopic(), meta.getTopic());
    Assert.assertEquals(META1.getShareGroupId(), meta.getShareGroupId());
    Assert.assertEquals(META1.getLastProcessedTimestamp(), meta.getLastProcessedTimestamp());
  }

  @Test
  public void testJsonSerializationWithNullTimestamp() throws Exception
  {
    String json = OBJECT_MAPPER.writeValueAsString(META0);
    DataSourceMetadata deserialized = OBJECT_MAPPER.readValue(json, DataSourceMetadata.class);

    Assert.assertTrue(deserialized instanceof KafkaShareGroupDataSourceMetadata);
    KafkaShareGroupDataSourceMetadata meta = (KafkaShareGroupDataSourceMetadata) deserialized;
    Assert.assertEquals(META0.getTopic(), meta.getTopic());
    Assert.assertEquals(META0.getShareGroupId(), meta.getShareGroupId());
    Assert.assertNull(meta.getLastProcessedTimestamp());
  }
}
