package io.github.aki1d.javaengineering.benchmarks;

import io.github.aki1d.javaengineering.cache.ExpiringLruCache;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Concurrent read throughput of {@link ExpiringLruCache} against a plain {@link ConcurrentHashMap}.
 *
 * <p>The cache takes a single lock on every read, because a read in an access-ordered map is also a
 * write (it moves the entry). This benchmark quantifies that cost, which is the main argument for
 * the read-buffer design used by Caffeine (see docs/cache.md).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class CacheReadBenchmark {

  private static final int KEYS = 10_000;

  private ExpiringLruCache<Integer, String> cache;
  private ConcurrentHashMap<Integer, String> map;

  @Setup
  public void setUp() {
    cache = new ExpiringLruCache<>(KEYS, Duration.ofHours(1));
    map = new ConcurrentHashMap<>();
    for (int i = 0; i < KEYS; i++) {
      cache.put(i, "value-" + i);
      map.put(i, "value-" + i);
    }
  }

  @Benchmark
  public Object expiringLruCacheHit() {
    return cache.getIfPresent(ThreadLocalRandom.current().nextInt(KEYS));
  }

  @Benchmark
  public Object concurrentHashMapHit() {
    return map.get(ThreadLocalRandom.current().nextInt(KEYS));
  }
}
