# Kafka Share Group Support for Apache Druid

## Overview

This package implements Kafka Share Group (KIP-932) support for Apache Druid, enabling queue-based consumption instead of traditional partition-based consumption.

### Key Differences from Regular Kafka Ingestion

| Feature | Regular Kafka | Share Groups |
|---------|--------------|--------------|
| Consumption Model | Partition-based | Queue-based |
| Partition Assignment | Explicit | None (broker-managed) |
| Offset Management | Client-tracked | Broker-managed |
| Parallelism | Based on partition count | Independent replicas |
| Kafka Version | Any | 4.0+ |

## Architecture

### Components

1. **KafkaShareGroupSupervisor** - Manages task lifecycle
   - Spawns replica tasks based on configuration
   - Monitors task health
   - Handles task rotation based on duration

2. **KafkaShareGroupIndexTask** - Ingestion worker
   - Polls records from share group
   - Processes and indexes data
   - Acknowledges records (ACCEPT/REJECT/RELEASE)
   - Publishes segments

3. **KafkaShareGroupRecordSupplier** - Kafka ShareConsumer wrapper
   - Wraps Kafka ShareConsumer API
   - Tracks pending acknowledgments
   - Manages commitSync operations

4. **KafkaShareGroupDataSourceMetadata** - Time-based metadata
   - Tracks last processed timestamp (not offsets)
   - Used for supervisor state management

## Configuration

### Supervisor Spec Example

```json
{
  "type": "kafka_share_group",
  "spec": {
    "dataSchema": {
      "dataSource": "my-datasource",
      "timestampSpec": {
        "column": "timestamp",
        "format": "auto"
      },
      "dimensionsSpec": {
        "dimensions": ["dimension1", "dimension2"]
      },
      "granularitySpec": {
        "segmentGranularity": "hour",
        "queryGranularity": "none"
      }
    },
    "ioConfig": {
      "topic": "my-topic",
      "shareGroupId": "druid-share-group",
      "inputFormat": {
        "type": "json"
      },
      "consumerProperties": {
        "bootstrap.servers": "localhost:9092"
      },
      "replicas": 2,
      "taskDuration": "PT1H",
      "checkpointPeriod": "PT25S",
      "pollTimeout": 100
    },
    "tuningConfig": {
      "maxRowsInMemory": 150000,
      "maxRowsPerSegment": 5000000,
      "intermediatePersistPeriod": "PT10M"
    }
  }
}
