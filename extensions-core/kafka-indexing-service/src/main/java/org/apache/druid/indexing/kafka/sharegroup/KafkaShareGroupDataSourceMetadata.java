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
import org.apache.druid.indexing.overlord.DataSourceMetadata;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Time-based metadata for Kafka Share Group ingestion (no offsets/partitions).
 */
public class KafkaShareGroupDataSourceMetadata implements DataSourceMetadata
{
  private final String topic;
  private final String shareGroupId;
  private final DateTime lastProcessedTimestamp;

  @JsonCreator
  public KafkaShareGroupDataSourceMetadata(
      @JsonProperty("topic") String topic,
      @JsonProperty("shareGroupId") String shareGroupId,
      @JsonProperty("lastProcessedTimestamp") @Nullable DateTime lastProcessedTimestamp
  )
  {
    this.topic = topic;
    this.shareGroupId = shareGroupId;
    this.lastProcessedTimestamp = lastProcessedTimestamp;
  }

  @JsonProperty
  public String getTopic()
  {
    return topic;
  }

  @JsonProperty
  public String getShareGroupId()
  {
    return shareGroupId;
  }

  @JsonProperty
  @Nullable
  public DateTime getLastProcessedTimestamp()
  {
    return lastProcessedTimestamp;
  }

  @Override
  public boolean matches(DataSourceMetadata other)
  {
    if (!(other instanceof KafkaShareGroupDataSourceMetadata)) {
      return false;
    }

    KafkaShareGroupDataSourceMetadata that = (KafkaShareGroupDataSourceMetadata) other;
    return Objects.equals(this.topic, that.topic) &&
           Objects.equals(this.shareGroupId, that.shareGroupId);
  }

  @Override
  public DataSourceMetadata plus(DataSourceMetadata other)
  {
    if (!(other instanceof KafkaShareGroupDataSourceMetadata)) {
      throw new IllegalArgumentException(
          String.format("Expected KafkaShareGroupDataSourceMetadata, got [%s]", other.getClass().getSimpleName())
      );
    }

    KafkaShareGroupDataSourceMetadata that = (KafkaShareGroupDataSourceMetadata) other;

    if (!matches(that)) {
      throw new IllegalArgumentException(
          String.format("Cannot merge metadata from different sources: [%s] vs [%s]", this, that)
      );
    }

    // Take max timestamp
    DateTime mergedTimestamp = this.lastProcessedTimestamp;
    if (that.lastProcessedTimestamp != null) {
      if (mergedTimestamp == null || that.lastProcessedTimestamp.isAfter(mergedTimestamp)) {
        mergedTimestamp = that.lastProcessedTimestamp;
      }
    }

    return new KafkaShareGroupDataSourceMetadata(topic, shareGroupId, mergedTimestamp);
  }

  @Override
  public DataSourceMetadata minus(DataSourceMetadata other)
  {
    // No range tracking in Share Groups, return unchanged
    return this;
  }

  public KafkaShareGroupDataSourceMetadata withLastProcessedTimestamp(DateTime timestamp)
  {
    return new KafkaShareGroupDataSourceMetadata(topic, shareGroupId, timestamp);
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
    KafkaShareGroupDataSourceMetadata that = (KafkaShareGroupDataSourceMetadata) o;
    return Objects.equals(topic, that.topic) &&
           Objects.equals(shareGroupId, that.shareGroupId) &&
           Objects.equals(lastProcessedTimestamp, that.lastProcessedTimestamp);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(topic, shareGroupId, lastProcessedTimestamp);
  }

  @Override
  public String toString()
  {
    return "KafkaShareGroupDataSourceMetadata{" +
           "topic='" + topic + '\'' +
           ", shareGroupId='" + shareGroupId + '\'' +
           ", lastProcessedTimestamp=" + lastProcessedTimestamp +
           '}';
  }
}
