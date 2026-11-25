package io.github.aki1d.javaengineering.benchmarks;

import io.github.aki1d.javaengineering.collections.IntArrayList;
import java.util.ArrayList;
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

/**
 * Sums a list of ints stored as primitives versus as boxed {@code Integer}s.
 *
 * <p>Values are random so that most of them fall outside the {@code Integer} cache (-128..127) and
 * really are separate heap objects. Expect the gap to grow with size, once the boxed list no longer
 * fits in CPU caches.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class IntListSumBenchmark {

  @Param({"1000", "1000000"})
  int size;

  private IntArrayList primitives;
  private List<Integer> boxed;

  @Setup
  public void setUp() {
    SplittableRandom random = new SplittableRandom(42);
    primitives = new IntArrayList(size);
    boxed = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      int value = random.nextInt();
      primitives.add(value);
      boxed.add(value);
    }
  }

  @Benchmark
  public long primitiveLoop() {
    return primitives.sum();
  }

  @Benchmark
  public long boxedLoop() {
    long sum = 0;
    for (Integer value : boxed) {
      sum += value;
    }
    return sum;
  }

  @Benchmark
  public long boxedStream() {
    return boxed.stream().mapToLong(Integer::longValue).sum();
  }
}
