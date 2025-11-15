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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Synthetic partition for Kafka Share Groups (no real partitions in queue model).
 * All records use partition 0 for compatibility with Druid's partition-based architecture.
 */
public class KafkaShareGroupPartition
{
  public static final int SYNTHETIC_PARTITION_ID = 0;
  public static final KafkaShareGroupPartition SYNTHETIC_PARTITION = new KafkaShareGroupPartition();

  private final String topic;

  @JsonCreator
  public KafkaShareGroupPartition(@JsonProperty("topic") String topic)
  {
    this.topic = topic;
  }

  private KafkaShareGroupPartition()
  {
    this.topic = null;
  }

  @JsonProperty
  public int partition()
  {
    return SYNTHETIC_PARTITION_ID;
  }

  @JsonProperty
  public String topic()
  {
    return topic;
  }

  public boolean isSynthetic()
  {
    return topic == null;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    KafkaShareGroupPartition that = (KafkaShareGroupPartition) o;
    return true; // All partitions equal (single queue)
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(SYNTHETIC_PARTITION_ID);
  }

  @Override
  public String toString()
  {
    if (isSynthetic()) {
      return "KafkaShareGroupPartition{SYNTHETIC}";
    }
    return "KafkaShareGroupPartition{" +
           "topic='" + topic + '\'' +
           ", partition=" + SYNTHETIC_PARTITION_ID +
           " (synthetic)" +
           '}';
  }
}
