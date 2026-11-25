package io.github.aki1d.javaengineering.benchmarks;

import io.github.aki1d.javaengineering.concurrent.LockFreeStack;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Push/pop throughput under contention: two pushing and two popping threads share one stack.
 *
 * <p>Compares the CAS-based {@link LockFreeStack} with a {@code synchronized} {@link ArrayDeque}
 * and the JDK's lock-free {@link ConcurrentLinkedDeque}. Results depend heavily on core count;
 * rerun with different {@code @GroupThreads} values before drawing conclusions.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Group)
public class LockFreeStackBenchmark {

  private static final Integer ITEM = 1;

  private final LockFreeStack<Integer> lockFree = new LockFreeStack<>();
  private final Deque<Integer> synchronizedDeque = new ArrayDeque<>();
  private final ConcurrentLinkedDeque<Integer> jdkDeque = new ConcurrentLinkedDeque<>();

  @Benchmark
  @Group("lockFree")
  @GroupThreads(2)
  public void lockFreePush() {
    lockFree.push(ITEM);
  }

  @Benchmark
  @Group("lockFree")
  @GroupThreads(2)
  public Object lockFreePop() {
    return lockFree.pop();
  }

  @Benchmark
  @Group("synchronizedDeque")
  @GroupThreads(2)
  public void synchronizedPush() {
    synchronized (synchronizedDeque) {
      synchronizedDeque.push(ITEM);
    }
  }

  @Benchmark
  @Group("synchronizedDeque")
  @GroupThreads(2)
  public Object synchronizedPop() {
    synchronized (synchronizedDeque) {
      return synchronizedDeque.pollFirst();
    }
  }

  @Benchmark
  @Group("concurrentLinkedDeque")
  @GroupThreads(2)
  public void jdkPush() {
    jdkDeque.push(ITEM);
  }

  @Benchmark
  @Group("concurrentLinkedDeque")
  @GroupThreads(2)
  public Object jdkPop() {
    return jdkDeque.pollFirst();
  }
}
