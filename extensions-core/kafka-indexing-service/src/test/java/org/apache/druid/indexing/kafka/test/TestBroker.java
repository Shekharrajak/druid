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

package org.apache.druid.indexing.kafka.test;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.Map;
import java.util.Properties;

/**
 * Stub for the old embedded Kafka broker used in pre-4.x tests.
 * <p>
 * Kafka 4.x removed {@code kafka.server.KafkaServer} (KRaft only).
 * Tests requiring an embedded Kafka should use {@code KafkaResource}
 * (Testcontainers) instead.
 */
public class TestBroker implements Closeable
{
  private static final UnsupportedOperationException NOT_SUPPORTED =
      new UnsupportedOperationException(
          "TestBroker is not supported with Kafka 4.x. Use KafkaResource (Testcontainers) instead."
      );

  public TestBroker(String zookeeperConnect, @Nullable String directoryBase, int id, Map<String, String> brokerProps)
  {
    // Constructor kept for compilation compatibility
  }

  public void start()
  {
    throw NOT_SUPPORTED;
  }

  public int getPort()
  {
    throw NOT_SUPPORTED;
  }

  public Map<String, Object> consumerProperties()
  {
    throw NOT_SUPPORTED;
  }

  public KafkaProducer<byte[], byte[]> newProducer()
  {
    throw NOT_SUPPORTED;
  }

  public Admin newAdminClient()
  {
    throw NOT_SUPPORTED;
  }

  @Override
  public void close()
  {
    // no-op
  }
}
