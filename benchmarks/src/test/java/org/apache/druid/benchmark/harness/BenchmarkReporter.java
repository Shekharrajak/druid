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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Runs a JMH benchmark class and produces:
 * <ul>
 *   <li>{@code benchmarks/results/&lt;ClassName&gt;.json} — raw JMH JSON (archived)</li>
 *   <li>{@code benchmarks/results/&lt;ClassName&gt;-result.txt} — human-readable markdown table</li>
 * </ul>
 *
 * Throughput rows are derived from each scenario's expected unit count, which the user passes
 * to {@link #runAndReport(Class, String, Map)}: a map of scenario method name → units produced
 * per op. (This is decoupled from {@link BenchmarkSubject#unitsProduced} because JMH doesn't
 * expose per-op state — only the average score — so the reporter needs the unit count out-of-band.)
 *
 * <p>Sample call:
 * <pre>{@code
 * public static void main(String[] args) throws Exception {
 *   Map<String, Long> unitsPerOp = Map.of(
 *       "icebergArrowInputSourceReader", 500_000L,
 *       "icebergInputSourceReader",      500_000L
 *   );
 *   BenchmarkReporter.runAndReport(
 *       IcebergReaderBenchmark.class,
 *       "icebergInputSourceReader",   // baseline scenario for speedup column
 *       unitsPerOp
 *   );
 * }
 * }</pre>
 */
public final class BenchmarkReporter
{
  private static final Path RESULTS_DIR = Paths.get("benchmarks", "results");
  private static final String UNIT_LABEL = "rows/s";

  private BenchmarkReporter()
  {
  }

  /**
   * Runs the benchmark with JMH defaults (2 warmup + 3 measurement iters, 1 fork) and writes
   * the report.
   *
   * @param benchmarkClass    the JMH benchmark class to run
   * @param baselineScenario  scenario name to use as the speedup baseline; null = auto-pick slowest
   * @param unitsPerOp        function returning units/op for a (scenario name, JMH params) tuple.
   *                          Returning null suppresses the throughput cell for that row.
   */
  public static void runAndReport(
      final Class<?> benchmarkClass,
      @Nullable final String baselineScenario,
      final BiFunction<String, Map<String, String>, Long> unitsPerOp
  ) throws Exception
  {
    runAndReport(benchmarkClass, baselineScenario, unitsPerOp, b -> { /* defaults */ });
  }

  /**
   * Same as {@link #runAndReport(Class, String, BiFunction)} but with a hook to customize JMH options.
   */
  public static void runAndReport(
      final Class<?> benchmarkClass,
      @Nullable final String baselineScenario,
      final BiFunction<String, Map<String, String>, Long> unitsPerOp,
      final Consumer<OptionsBuilder> jmhOptionsCustomizer
  ) throws Exception
  {
    Files.createDirectories(RESULTS_DIR);

    final String className = benchmarkClass.getSimpleName();
    final Path jsonPath = RESULTS_DIR.resolve(className + ".json");
    final Path txtPath = RESULTS_DIR.resolve(className + "-result.txt");

    final OptionsBuilder opts = new OptionsBuilder();
    opts.include(benchmarkClass.getSimpleName())
        .resultFormat(ResultFormatType.JSON)
        .result(jsonPath.toString());
    jmhOptionsCustomizer.accept(opts);

    new Runner(opts.build()).run();

    final String report = buildReport(jsonPath, className, baselineScenario, unitsPerOp);
    try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(txtPath, StandardCharsets.UTF_8))) {
      pw.println(report);
    }
    System.out.println("\nReport written to: " + txtPath.toAbsolutePath());
    System.out.println(report);
  }

  static String buildReport(
      final Path jsonPath,
      final String benchmarkName,
      @Nullable final String baselineScenario,
      final BiFunction<String, Map<String, String>, Long> unitsPerOp
  ) throws IOException
  {
    final List<Row> rows = parseJson(jsonPath);
    if (rows.isEmpty()) {
      return "No benchmark results found in " + jsonPath;
    }

    // Group by parameter tuple, then by scenario name.
    final Map<String, Map<String, Row>> grouped = new TreeMap<>();
    for (final Row r : rows) {
      grouped.computeIfAbsent(r.paramsKey, k -> new LinkedHashMap<>()).put(r.scenario, r);
    }

    // Collect scenario names in stable order (sorted alphabetically; baseline last if present).
    final List<String> scenarios = new ArrayList<>();
    for (final Map<String, Row> byScenario : grouped.values()) {
      for (final String s : byScenario.keySet()) {
        if (!scenarios.contains(s)) {
          scenarios.add(s);
        }
      }
    }
    scenarios.sort(Comparator.naturalOrder());
    final String effectiveBaseline = baselineScenario != null && scenarios.contains(baselineScenario)
                                     ? baselineScenario
                                     : null;

    // Collect parameter keys (consistent across rows).
    final List<String> paramKeys = rows.get(0).params.keySet().stream().sorted().collect(java.util.stream.Collectors.toList());

    final StringBuilder out = new StringBuilder();
    out.append(banner());
    out.append(benchmarkName).append('\n');
    out.append("Generated: ").append(Instant.now()).append('\n');
    out.append("JMH: avg time per op (ms)\n");
    out.append("Throughput unit: ").append(UNIT_LABEL).append('\n');
    out.append("Baseline: ").append(effectiveBaseline != null ? effectiveBaseline : "(none)").append('\n');
    out.append(banner());

    // Header
    out.append('\n');
    out.append("| ");
    for (final String pk : paramKeys) {
      out.append(pad(pk, 10)).append(" | ");
    }
    for (final String s : scenarios) {
      out.append(pad(s + " (ms / throughput)", 44)).append(" | ");
    }
    if (effectiveBaseline != null) {
      out.append(pad("Speedup", 8)).append(" |");
    }
    out.append('\n');

    out.append('|');
    for (int i = 0; i < paramKeys.size(); i++) {
      out.append(repeat('-', 12)).append('|');
    }
    for (int i = 0; i < scenarios.size(); i++) {
      out.append(repeat('-', 46)).append('|');
    }
    if (effectiveBaseline != null) {
      out.append(repeat('-', 10)).append('|');
    }
    out.append('\n');

    final List<Double> speedups = new ArrayList<>();
    String bestParams = null;
    double bestSpeedup = 0;
    String worstParams = null;
    double worstSpeedup = Double.POSITIVE_INFINITY;

    for (final Map.Entry<String, Map<String, Row>> e : grouped.entrySet()) {
      final Map<String, Row> byScenario = e.getValue();
      out.append("| ");
      for (final String pk : paramKeys) {
        final Row anyRow = byScenario.values().iterator().next();
        out.append(pad(anyRow.params.getOrDefault(pk, "?"), 10)).append(" | ");
      }
      Double baseScore = null;
      for (final String s : scenarios) {
        final Row r = byScenario.get(s);
        if (r == null) {
          out.append(pad("n/a", 44)).append(" | ");
        } else {
          final Long units = unitsPerOp.apply(s, r.params);
          final String throughput = units != null
                                    ? String.format(Locale.ROOT, "%,.0f %s", units / (r.scoreMs / 1000.0), UNIT_LABEL)
                                    : "n/a";
          final String cell = String.format(Locale.ROOT, "%8.2f ms  %s", r.scoreMs, throughput);
          out.append(pad(cell, 44)).append(" | ");
          if (effectiveBaseline != null && s.equals(effectiveBaseline)) {
            baseScore = r.scoreMs;
          }
        }
      }
      if (effectiveBaseline != null) {
        final Row fastest = byScenario.values().stream().min(Comparator.comparingDouble(x -> x.scoreMs)).orElse(null);
        if (fastest != null && baseScore != null && fastest.scoreMs > 0 && !fastest.scenario.equals(effectiveBaseline)) {
          final double speedup = baseScore / fastest.scoreMs;
          out.append(pad(String.format(Locale.ROOT, "%.2fx", speedup), 8)).append(" |");
          speedups.add(speedup);
          if (speedup > bestSpeedup) {
            bestSpeedup = speedup;
            bestParams = e.getKey();
          }
          if (speedup < worstSpeedup) {
            worstSpeedup = speedup;
            worstParams = e.getKey();
          }
        } else {
          out.append(pad("—", 8)).append(" |");
        }
      }
      out.append('\n');
    }

    out.append(banner());
    if (!speedups.isEmpty()) {
      final double geomean = Math.exp(speedups.stream().mapToDouble(Math::log).average().orElse(0));
      out.append(String.format(
          Locale.ROOT,
          "Summary:  best %.2fx (%s) | worst %.2fx (%s) | geomean %.2fx%n",
          bestSpeedup, bestParams, worstSpeedup, worstParams, geomean
      ));
      out.append(banner());
    }
    return out.toString();
  }

  private static List<Row> parseJson(final Path jsonPath) throws IOException
  {
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode root = mapper.readTree(jsonPath.toFile());
    final List<Row> rows = new ArrayList<>();
    for (final JsonNode node : root) {
      final String bm = node.get("benchmark").asText();
      final String scenario = bm.substring(bm.lastIndexOf('.') + 1);
      final double score = node.get("primaryMetric").get("score").asDouble();
      final Map<String, String> params = new TreeMap<>();
      if (node.has("params")) {
        final JsonNode p = node.get("params");
        p.fieldNames().forEachRemaining(name -> params.put(name, p.get(name).asText()));
      }
      final String paramsKey = params.toString();
      rows.add(new Row(scenario, params, paramsKey, score));
    }
    return rows;
  }

  private static String banner()
  {
    return "================================================================================\n";
  }

  private static String pad(final String s, final int width)
  {
    if (s.length() >= width) {
      return s;
    }
    final StringBuilder sb = new StringBuilder(s);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  private static String repeat(final char c, final int n)
  {
    final char[] arr = new char[n];
    java.util.Arrays.fill(arr, c);
    return new String(arr);
  }

  private static final class Row
  {
    final String scenario;
    final Map<String, String> params;
    final String paramsKey;
    final double scoreMs;

    Row(final String scenario, final Map<String, String> params, final String paramsKey, final double scoreMs)
    {
      this.scenario = scenario;
      this.params = params;
      this.paramsKey = paramsKey;
      this.scoreMs = scoreMs;
    }
  }
}
