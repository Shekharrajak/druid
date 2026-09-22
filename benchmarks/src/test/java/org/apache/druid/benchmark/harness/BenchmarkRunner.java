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

import org.openjdk.jmh.infra.Blackhole;

/**
 * Tiny, stateless helper invoked from inside a {@code @Benchmark} method.
 *
 * Composition over inheritance: do NOT extend this class from your JMH {@code @State} class;
 * JMH bytecode-generates subclasses already and inheritance fights that. Call the static helper
 * directly.
 *
 * <pre>{@code
 * @Benchmark
 * public void myScenario(Blackhole bh) throws Exception {
 *   BenchmarkRunner.measure(new MyReaderSubject(...), bh);
 * }
 * }</pre>
 */
public final class BenchmarkRunner
{
  private BenchmarkRunner()
  {
  }

  /**
   * Execute the subject once, feed its output to the {@link Blackhole}, and verify it actually
   * produced something (catches no-op bugs in benchmark code early).
   */
  public static <O> void measure(final BenchmarkSubject<O> subject, final Blackhole bh) throws Exception
  {
    final O output = subject.execute();
    bh.consume(output);
    final long units = subject.unitsProduced(output);
    if (units <= 0) {
      throw new IllegalStateException(
          "Subject [" + subject.name() + "] produced " + units + " units — benchmark is likely a no-op"
      );
    }
    bh.consume(units);
  }

  /**
   * Same as {@link #measure} but asserts the subject produced exactly {@code expectedUnits}.
   * Use when you want a correctness guard on the benchmark (recommended for ingestion paths
   * where wrong row counts indicate a real bug rather than a perf regression).
   */
  public static <O> void measureAndVerify(
      final BenchmarkSubject<O> subject,
      final Blackhole bh,
      final long expectedUnits
  ) throws Exception
  {
    final O output = subject.execute();
    bh.consume(output);
    final long units = subject.unitsProduced(output);
    if (units != expectedUnits) {
      throw new IllegalStateException(
          "Subject [" + subject.name() + "] produced " + units + " units, expected " + expectedUnits
      );
    }
    bh.consume(units);
  }
}
