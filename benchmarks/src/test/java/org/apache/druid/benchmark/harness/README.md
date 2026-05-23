# Druid Benchmark Harness

Tiny, modular JMH benchmarking helper. Three files, one interface, one runner helper, one
reporter. Use it for any new feature/API in the Druid ecosystem.

## Files

| File | Purpose |
|---|---|
| `BenchmarkSubject.java` | Implement once per scenario: `name()`, `execute()`, `unitsProduced()`. |
| `BenchmarkRunner.java`  | Static helper called from inside `@Benchmark` methods. |
| `BenchmarkReporter.java`| Runs JMH and emits `benchmarks/results/<ClassName>-result.txt` + raw JSON. |

## Write a new benchmark in 20 lines

```java
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class MyFeatureBenchmark {
  @Param({"100000", "500000"}) public long inputRows;

  @Setup(Level.Trial) public void setup() { /* build inputs once */ }

  @Benchmark public void newImpl(Blackhole bh) throws Exception {
    BenchmarkRunner.measure(subjectFor("newImpl", () -> buildNew()), bh);
  }
  @Benchmark public void existingImpl(Blackhole bh) throws Exception {
    BenchmarkRunner.measure(subjectFor("existingImpl", () -> buildOld()), bh);
  }

  public static void main(String[] args) throws Exception {
    BenchmarkReporter.runAndReport(
        MyFeatureBenchmark.class,
        "existingImpl",                             // baseline for speedup column (nullable)
        Map.of("newImpl", inputRows, "existingImpl", inputRows)
    );
  }
}
```

## How to add a new domain

Implement `BenchmarkSubject<O>`. Example for segment writers:

```java
public final class SegmentWriterSubject implements BenchmarkSubject<DataSegment> {
  private final String name;
  private final Callable<DataSegment> work;
  public SegmentWriterSubject(String name, Callable<DataSegment> work) {
    this.name = name; this.work = work;
  }
  @Override public String name() { return name; }
  @Override public DataSegment execute() throws Exception { return work.call(); }
  @Override public long unitsProduced(DataSegment s) { return s.getSize(); }
  @Override public String unitLabel() { return "bytes/s"; }
}
```

No harness changes needed.

## Run

```bash
mvn package -pl benchmarks -DskipTests -Pskip-static-checks -Dweb.console.skip=true
java -jar benchmarks/target/benchmarks.jar MyFeatureBenchmark   # via main(): also writes result.txt
```

Or invoke `main()` of the benchmark class directly from your IDE.

## Output

- `benchmarks/results/MyFeatureBenchmark.json` — raw JMH JSON (deep-dive, archival)
- `benchmarks/results/MyFeatureBenchmark-result.txt` — markdown table for humans

The table includes throughput per scenario, and a Speedup column vs the named baseline.
