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

package org.apache.druid.benchmark.harness;

/**
 * A single unit of work to be timed by {@link BenchmarkRunner}.
 *
 * Implement once per domain you want to benchmark (reader, writer, query, MSQ stage, ...).
 * The harness stays domain-agnostic: it only knows how to call {@link #execute()}, count units
 * produced, and feed the output to a JMH {@code Blackhole}.
 *
 * @param <O> the type produced by {@link #execute()} — used only to feed the Blackhole and to
 *            compute {@link #unitsProduced(Object)} for throughput math.
 */
public interface BenchmarkSubject<O>
{
  /**
   * Human-readable scenario name. Surfaces in the generated result table.
   * Convention: match the class or method being exercised (e.g. {@code "icebergArrowInputSourceReader"}).
   */
  String name();

  /**
   * Perform the work being benchmarked. Called once per JMH operation.
   */
  O execute() throws Exception;

  /**
   * Number of units this op produced — rows, bytes, queries, segments, whatever.
   * Used by the report writer to compute throughput as {@code unitsProduced / scoreSeconds}.
   * Return {@code 1} for "ops/sec"-style throughput.
   */
  long unitsProduced(O output);

  /**
   * Label for the throughput unit ({@code "rows/s"}, {@code "bytes/s"}, {@code "ops/s"}, ...).
   * Surfaces in the generated report header.
   */
  default String unitLabel()
  {
    return "rows/s";
  }
}
