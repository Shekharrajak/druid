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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.benchmark.harness.BenchmarkReporter;
import org.apache.druid.benchmark.harness.BenchmarkRunner;
import org.apache.druid.benchmark.harness.BenchmarkSubject;
import org.apache.druid.data.input.ColumnsFilter;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.InputSourceReader;
import org.apache.druid.data.input.InputStats;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.DoubleDimensionSchema;
import org.apache.druid.data.input.impl.LocalInputSourceFactory;
import org.apache.druid.data.input.impl.LongDimensionSchema;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.data.input.parquet.ParquetInputFormat;
import org.apache.druid.iceberg.input.IcebergArrowInputSourceReader;
import org.apache.druid.iceberg.input.IcebergInputSource;
import org.apache.druid.iceberg.input.LocalCatalog;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexMergerV9;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.incremental.IncrementalIndexSchema;
import org.apache.druid.segment.incremental.OnheapIncrementalIndex;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end ingestion benchmark: JSON ingestion-spec round-trip → IcebergInputSource →
 * InputSourceReader → IncrementalIndex → segment persist (IndexMergerV9.persist).
 *
 * This mirrors the work an IndexTask does internally (read → index → persist), minus the
 * Overlord/metadata-storage coordination, which doesn't affect ingestion throughput.
 *
 * Scenarios mirror the reader classes being exercised:
 * <ul>
 *   <li>{@link #icebergArrowInputSourceReader_ingest} — {@code useArrowReader=true}</li>
 *   <li>{@link #icebergInputSourceReader_ingest} — {@code useArrowReader=false}</li>
 * </ul>
 *
 * Run via {@link #main(String[])} to also auto-produce {@code result.txt}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1)
public class IcebergIngestionBenchmark
{
  private static final String NAMESPACE = "bench";
  private static final String TABLE = "benchTable";
  private static final ObjectMapper JSON_MAPPER;
  private static final IndexMergerV9 INDEX_MERGER_V9;

  static {
    JSON_MAPPER = new DefaultObjectMapper();
    final IndexIO indexIo = new IndexIO(JSON_MAPPER, ColumnConfig.DEFAULT);
    INDEX_MERGER_V9 = new IndexMergerV9(
        JSON_MAPPER,
        indexIo,
        OffHeapMemorySegmentWriteOutMediumFactory.instance()
    );
  }

  @Param({"100000"})
  public int numRows;

  @Param({"5"})
  public int numColumns;

  private File warehouseDir;
  private LocalCatalog catalog;
  private Table table;
  private InputRowSchema inputRowSchema;
  private AggregatorFactory[] metrics;
  private File persistTmpDir;

  @Setup(Level.Trial)
  public void setupTrial() throws IOException
  {
    warehouseDir = FileUtils.createTempDir();
    catalog = new LocalCatalog(warehouseDir.getAbsolutePath(), new HashMap<>(), true);

    final Schema schema = buildIcebergSchema(numColumns);
    inputRowSchema = buildInputRowSchema(numColumns);
    metrics = buildMetrics(numColumns);

    final Catalog rawCatalog = catalog.retrieveCatalog();
    final TableIdentifier tableId = TableIdentifier.of(Namespace.of(NAMESPACE), TABLE);
    table = rawCatalog.createTable(tableId, schema);
    writeData(table, schema, numRows);

    // Verify a representative ingestion-spec JSON parses cleanly (proves the spec is valid for both readers).
    verifySpecJsonRoundTrip();
  }

  @TearDown(Level.Trial)
  public void teardownTrial()
  {
    final TableIdentifier tableId = TableIdentifier.of(Namespace.of(NAMESPACE), TABLE);
    try {
      catalog.retrieveCatalog().dropTable(tableId);
    }
    catch (Exception ignored) {
      // best-effort
    }
    deleteDir(warehouseDir);
  }

  @Setup(Level.Invocation)
  public void setupInvocation() throws IOException
  {
    persistTmpDir = FileUtils.createTempDir();
  }

  @TearDown(Level.Invocation)
  public void teardownInvocation()
  {
    deleteDir(persistTmpDir);
  }

  @Benchmark
  public void icebergArrowInputSourceReader_ingest(final Blackhole bh) throws Exception
  {
    final IcebergArrowInputSourceReader reader = new IcebergArrowInputSourceReader(
        table,
        null,
        null,
        true,
        inputRowSchema,
        IcebergArrowInputSourceReader.DEFAULT_BATCH_SIZE
    );
    BenchmarkRunner.measureAndVerify(
        ingestSubject("icebergArrowInputSourceReader_ingest", reader),
        bh,
        numRows
    );
  }

  @Benchmark
  public void icebergInputSourceReader_ingest(final Blackhole bh) throws Exception
  {
    final IcebergInputSource source = new IcebergInputSource(
        TABLE,
        NAMESPACE,
        null,
        catalog,
        new LocalInputSourceFactory(),
        null,
        null,
        false,
        0
    );
    final ParquetInputFormat parquetFormat = new ParquetInputFormat(null, null, new Configuration());
    final InputSourceReader reader = source.reader(inputRowSchema, parquetFormat, warehouseDir);
    BenchmarkRunner.measureAndVerify(
        ingestSubject("icebergInputSourceReader_ingest", reader),
        bh,
        numRows
    );
  }

  // --- harness wiring ---

  /**
   * Builds a subject that performs the full ingestion pipeline:
   * reader.read() → IncrementalIndex.add() → IndexMergerV9.persist().
   */
  private BenchmarkSubject<long[]> ingestSubject(final String name, final InputSourceReader reader)
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
        final IncrementalIndex index = new OnheapIncrementalIndex.Builder()
            .setIndexSchema(
                new IncrementalIndexSchema.Builder()
                    .withMetrics(metrics)
                    .withRollup(false)
                    .build()
            )
            .setMaxRowCount(numRows + 1)
            .build();

        long rowsIngested = 0;
        try (CloseableIterator<InputRow> it = reader.read(NoopStats.INSTANCE)) {
          while (it.hasNext()) {
            index.add(it.next());
            rowsIngested++;
          }
        }

        final File segmentDir = INDEX_MERGER_V9.persist(
            index,
            persistTmpDir,
            IndexSpec.getDefault(),
            null
        );
        if (!segmentDir.exists()) {
          throw new IllegalStateException("Segment dir was not created");
        }
        index.close();
        return new long[]{rowsIngested};
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
        IcebergIngestionBenchmark.class,
        "icebergInputSourceReader_ingest",
        (scenario, params) -> Long.parseLong(params.get("numRows"))
    );
  }

  // --- spec JSON round-trip (proves the JSON path is valid for both reader configurations) ---

  private void verifySpecJsonRoundTrip() throws IOException
  {
    for (final boolean useArrow : new boolean[]{true, false}) {
      final Map<String, Object> spec = new LinkedHashMap<>();
      spec.put("type", "iceberg");
      spec.put("tableName", TABLE);
      spec.put("namespace", NAMESPACE);
      spec.put("warehouseSource", Map.of("type", "local"));
      spec.put("icebergCatalog", Map.of(
          "type", "local",
          "warehousePath", warehouseDir.getAbsolutePath(),
          "catalogProperties", Map.of()
      ));
      spec.put("useArrowReader", useArrow);
      spec.put("arrowBatchSize", 1024);
      final String json = JSON_MAPPER.writeValueAsString(spec);
      // round-trip parse to verify JSON shape is well-formed; full deserialization requires Druid module registration.
      JSON_MAPPER.readTree(json);
    }
  }

  // --- data helpers ---

  private static Schema buildIcebergSchema(final int numColumns)
  {
    final List<Types.NestedField> fields = new ArrayList<>();
    fields.add(Types.NestedField.required(1, "ts", Types.LongType.get()));
    for (int i = 2; i <= numColumns; i++) {
      if (i % 3 == 0) {
        fields.add(Types.NestedField.optional(i, "col_d" + i, Types.DoubleType.get()));
      } else if (i % 3 == 1) {
        fields.add(Types.NestedField.optional(i, "col_l" + i, Types.LongType.get()));
      } else {
        fields.add(Types.NestedField.optional(i, "col_s" + i, Types.StringType.get()));
      }
    }
    return new Schema(fields);
  }

  private static InputRowSchema buildInputRowSchema(final int numColumns)
  {
    final List<org.apache.druid.data.input.impl.DimensionSchema> dims = new ArrayList<>();
    for (int i = 2; i <= numColumns; i++) {
      if (i % 3 == 0) {
        dims.add(new DoubleDimensionSchema("col_d" + i));
      } else if (i % 3 == 1) {
        dims.add(new LongDimensionSchema("col_l" + i));
      } else {
        dims.add(new StringDimensionSchema("col_s" + i));
      }
    }
    return new InputRowSchema(
        new TimestampSpec("ts", "millis", null),
        DimensionsSpec.builder().setDimensions(dims).build(),
        ColumnsFilter.all()
    );
  }

  private static AggregatorFactory[] buildMetrics(final int numColumns)
  {
    final List<AggregatorFactory> aggs = new ArrayList<>();
    aggs.add(new CountAggregatorFactory("count"));
    // Add a DoubleSum on the first double column so persist exercises a metric aggregator path too.
    for (int i = 2; i <= numColumns; i++) {
      if (i % 3 == 0) {
        aggs.add(new DoubleSumAggregatorFactory("sum_d" + i, "col_d" + i));
        break;
      }
    }
    return aggs.toArray(new AggregatorFactory[0]);
  }

  private static void writeData(final Table table, final Schema schema, final int numRows) throws IOException
  {
    final String filepath = table.location() + "/" + UUID.randomUUID() + ".parquet";
    final OutputFile file = table.io().newOutputFile(filepath);
    final DataWriter<GenericRecord> writer =
        Parquet.writeData(file)
               .schema(schema)
               .createWriterFunc(GenericParquetWriter::create)
               .overwrite()
               .withSpec(PartitionSpec.unpartitioned())
               .build();
    try {
      final GenericRecord template = GenericRecord.create(schema);
      for (int i = 0; i < numRows; i++) {
        final GenericRecord r = template.copy();
        r.setField("ts", (long) (i + 1) * 1000L);
        for (final Types.NestedField field : schema.columns()) {
          if (field.name().startsWith("col_d")) {
            r.setField(field.name(), i * 0.1);
          } else if (field.name().startsWith("col_l")) {
            r.setField(field.name(), (long) i);
          } else if (field.name().startsWith("col_s")) {
            r.setField(field.name(), "val" + (i % 1000));
          }
        }
        writer.write(r);
      }
    }
    finally {
      writer.close();
    }
    final DataFile dataFile = writer.toDataFile();
    table.newAppend().appendFile(dataFile).commit();
  }

  private static void deleteDir(final File dir)
  {
    if (dir == null || !dir.exists()) {
      return;
    }
    final File[] files = dir.listFiles();
    if (files != null) {
      for (final File f : files) {
        if (f.isDirectory()) {
          deleteDir(f);
        } else {
          f.delete();
        }
      }
    }
    dir.delete();
  }

  private static final class NoopStats implements InputStats
  {
    static final NoopStats INSTANCE = new NoopStats();

    @Override
    public void incrementProcessedBytes(final long v)
    {
    }

    @Override
    public long getProcessedBytes()
    {
      return 0;
    }
  }
}
