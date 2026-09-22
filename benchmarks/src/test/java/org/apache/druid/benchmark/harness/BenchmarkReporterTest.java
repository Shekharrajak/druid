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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

public class BenchmarkReporterTest
{
  @TempDir
  public Path temporaryDirectory;

  @Test
  public void testBuildReportIncludesSlowdown() throws Exception
  {
    final Path jsonPath = temporaryDirectory.resolve("benchmark.json");
    Files.writeString(
        jsonPath,
        "["
        + "{\"benchmark\":\"example.arrow\",\"params\":{\"table\":\"lineitem\"},"
        + "\"primaryMetric\":{\"score\":200.0}},"
        + "{\"benchmark\":\"example.standard\",\"params\":{\"table\":\"lineitem\"},"
        + "\"primaryMetric\":{\"score\":100.0}}"
        + "]"
    );

    final String report = BenchmarkReporter.buildReport(
        jsonPath,
        "ExampleBenchmark",
        "standard",
        (scenario, params) -> 1L
    );

    Assertions.assertTrue(report.contains("0.50x"));
    Assertions.assertTrue(report.contains("geomean 0.50x"));
  }
}
