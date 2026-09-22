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

package org.apache.druid.benchmark;

import org.apache.druid.benchmark.harness.BenchmarkReporter;
import org.apache.druid.benchmark.harness.BenchmarkRunner;
import org.apache.druid.benchmark.harness.BenchmarkSubject;
import org.apache.druid.data.input.ColumnsFilter;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.InputSourceReader;
import org.apache.druid.data.input.InputStats;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.LocalInputSourceFactory;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.data.input.parquet.ParquetInputFormat;
import org.apache.druid.iceberg.input.IcebergArrowInputSourceReader;
import org.apache.druid.iceberg.input.IcebergInputSource;
import org.apache.druid.iceberg.input.LocalCatalog;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1)
public class IcebergTpchReaderBenchmark
{
  private static final String WAREHOUSE_ENV = "DRUID_TPCH_ICEBERG_WAREHOUSE";
  private static final String NAMESPACE_ENV = "DRUID_TPCH_ICEBERG_NAMESPACE";
  private static final String DEFAULT_WAREHOUSE = "/tmp/comet-bench/iceberg-warehouse";
  private static final String DEFAULT_NAMESPACE = "tpch";
  private static final int ARROW_BATCH_SIZE = 1024;
  private static final Map<String, Long> SF1_ROW_COUNTS = Map.of(
      "customer", 150_000L,
      "lineitem", 6_001_215L,
      "nation", 25L,
      "orders", 1_500_000L,
      "part", 200_000L,
      "partsupp", 800_000L,
      "region", 5L,
      "supplier", 10_000L
  );

  @Param({"customer", "lineitem", "nation", "orders", "part", "partsupp", "region", "supplier"})
  public String tableName;

  private File temporaryDirectory;
  private LocalCatalog catalog;
  private Table table;
  private InputRowSchema inputRowSchema;
  private long expectedRows;

  @Setup(Level.Trial)
  public void setup() throws IOException
  {
    final String warehouse = System.getenv().getOrDefault(WAREHOUSE_ENV, DEFAULT_WAREHOUSE);
    final String namespace = System.getenv().getOrDefault(NAMESPACE_ENV, DEFAULT_NAMESPACE);
    if (!new File(warehouse).isDirectory()) {
      throw new IllegalStateException(
          "TPC-H Iceberg warehouse [" + warehouse + "] does not exist; set [" + WAREHOUSE_ENV + "]"
      );
    }

    expectedRows = SF1_ROW_COUNTS.get(tableName);
    temporaryDirectory = FileUtils.createTempDir();
    catalog = new LocalCatalog(warehouse, new HashMap<>(), true);
    table = catalog.retrieveCatalog().loadTable(TableIdentifier.of(namespace, tableName));
    inputRowSchema = new InputRowSchema(
        new TimestampSpec(null, null, DateTimes.EPOCH),
        DimensionsSpec.builder().useSchemaDiscovery(true).build(),
        ColumnsFilter.all()
    );
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException
  {
    FileUtils.deleteDirectory(temporaryDirectory);
  }

  @Benchmark
  public void icebergArrowReader(final Blackhole blackhole) throws Exception
  {
    final InputSourceReader reader = new IcebergArrowInputSourceReader(
        table,
        null,
        null,
        true,
        inputRowSchema,
        ARROW_BATCH_SIZE
    );
    BenchmarkRunner.measureAndVerify(readerSubject("icebergArrowReader", reader), blackhole, expectedRows);
  }

  @Benchmark
  public void icebergStandardReader(final Blackhole blackhole) throws Exception
  {
    final String namespace = System.getenv().getOrDefault(NAMESPACE_ENV, DEFAULT_NAMESPACE);
    final IcebergInputSource source = new IcebergInputSource(
        tableName,
        namespace,
        null,
        catalog,
        new LocalInputSourceFactory(),
        null,
        null,
        false,
        0
    );
    final ParquetInputFormat parquetFormat = new ParquetInputFormat(null, null, new Configuration());
    final InputSourceReader reader = source.reader(inputRowSchema, parquetFormat, temporaryDirectory);
    BenchmarkRunner.measureAndVerify(readerSubject("icebergStandardReader", reader), blackhole, expectedRows);
  }

  private static BenchmarkSubject<long[]> readerSubject(final String name, final InputSourceReader reader)
  {
    return new BenchmarkSubject<long[]>()
    {
      @Override
      public String name()
      {
        return name;
      }

      @Override
      public long[] execute() throws Exception
      {
        long count = 0;
        long timestampChecksum = 0;
        try (CloseableIterator<InputRow> rows = reader.read(NoopStats.INSTANCE)) {
          while (rows.hasNext()) {
            timestampChecksum += rows.next().getTimestampFromEpoch();
            count++;
          }
        }
        return new long[]{count, timestampChecksum};
      }

      @Override
      public long unitsProduced(final long[] output)
      {
        return output[0];
      }
    };
  }

  public static void main(final String[] args) throws Exception
  {
    BenchmarkReporter.runAndReport(
        IcebergTpchReaderBenchmark.class,
        "icebergStandardReader",
        (scenario, params) -> SF1_ROW_COUNTS.get(params.get("tableName"))
    );
  }

  private enum NoopStats implements InputStats
  {
    INSTANCE;

    @Override
    public void incrementProcessedBytes(final long incrementByValue)
    {
    }

    @Override
    public long getProcessedBytes()
    {
      return 0;
    }
  }
}
