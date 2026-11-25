package io.github.aki1d.javaengineering.benchmarks;

import io.github.aki1d.javaengineering.collections.MoreCollectors;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Heap-based top-k (O(n log k)) versus sort-then-limit (O(n log n)). */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class TopKBenchmark {

  private static final int SIZE = 1_000_000;

  @Param({"10", "1000"})
  int k;

  private List<Integer> values;

  @Setup
  public void setUp() {
    values = new SplittableRandom(7).ints(SIZE).boxed().toList();
  }

  @Benchmark
  public List<Integer> heapTopK() {
    return values.stream().collect(MoreCollectors.topK(k, Comparator.naturalOrder()));
  }

  @Benchmark
  public List<Integer> sortThenLimit() {
    return values.stream().sorted(Comparator.reverseOrder()).limit(k).toList();
  }
}
