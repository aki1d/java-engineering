package io.github.aki1d.javaengineering.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExpiringLruCacheTest {

  private static final Duration TTL = Duration.ofSeconds(30);

  private final AtomicLong clock = new AtomicLong();

  private <K, V> ExpiringLruCache<K, V> cache(int maximumSize) {
    return new ExpiringLruCache<>(maximumSize, TTL, clock::get);
  }

  @Test
  void loadsOnMissAndServesFromCacheOnHit() {
    ExpiringLruCache<String, String> cache = cache(10);
    AtomicInteger loaderCalls = new AtomicInteger();

    String first = cache.get("k", key -> "v" + loaderCalls.incrementAndGet());
    String second = cache.get("k", key -> "v" + loaderCalls.incrementAndGet());

    assertThat(first).isEqualTo("v1");
    assertThat(second).isEqualTo("v1");
    assertThat(cache.stats()).isEqualTo(new CacheStats(1, 1, 1, 0));
    assertThat(cache.stats().hitRate()).isEqualTo(0.5);
  }

  @Test
  void entriesExpireAfterTheirTimeToLive() {
    ExpiringLruCache<String, String> cache = cache(10);
    cache.put("k", "v");

    clock.addAndGet(TTL.toNanos() - 1);
    assertThat(cache.getIfPresent("k")).contains("v");

    clock.addAndGet(1);
    assertThat(cache.getIfPresent("k")).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  void expiryIsCorrectWhenTheTickerOverflows() {
    clock.set(Long.MAX_VALUE - 10);
    ExpiringLruCache<String, String> cache = cache(10);
    cache.put("k", "v");

    clock.addAndGet(20); // wraps around to a negative value
    assertThat(cache.getIfPresent("k")).contains("v");

    clock.addAndGet(TTL.toNanos());
    assertThat(cache.getIfPresent("k")).isEmpty();
  }

  @Test
  void evictsLeastRecentlyUsedEntryWhenFull() {
    ExpiringLruCache<String, Integer> cache = cache(2);
    cache.put("a", 1);
    cache.put("b", 2);
    cache.getIfPresent("a"); // "b" is now the least recently used

    cache.put("c", 3);

    assertThat(cache.getIfPresent("b")).isEmpty();
    assertThat(cache.getIfPresent("a")).contains(1);
    assertThat(cache.getIfPresent("c")).contains(3);
    assertThat(cache.stats().evictionCount()).isEqualTo(1);
  }

  @Test
  void cleanUpRemovesOnlyExpiredEntries() {
    ExpiringLruCache<String, String> cache = cache(10);
    cache.put("old", "1");
    clock.addAndGet(TTL.toNanos() / 2);
    cache.put("new", "2");
    clock.addAndGet(TTL.toNanos() / 2);

    cache.cleanUp();

    assertThat(cache.size()).isEqualTo(1);
    assertThat(cache.getIfPresent("new")).contains("2");
  }

  @Test
  void invalidateRemovesTheEntry() {
    ExpiringLruCache<String, String> cache = cache(10);
    cache.put("k", "v");

    cache.invalidate("k");

    assertThat(cache.getIfPresent("k")).isEmpty();
  }

  @Test
  void concurrentMissesOnTheSameKeyRunTheLoaderOnce() throws Exception {
    ExpiringLruCache<String, String> cache = cache(10);
    AtomicInteger loaderCalls = new AtomicInteger();
    CountDownLatch loaderEntered = new CountDownLatch(1);
    CountDownLatch releaseLoader = new CountDownLatch(1);
    int callers = 32;

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(
            executor.submit(
                () ->
                    cache.get(
                        "hot",
                        key -> {
                          loaderCalls.incrementAndGet();
                          loaderEntered.countDown();
                          awaitUninterruptibly(releaseLoader);
                          return "value";
                        })));
      }
      loaderEntered.await();
      releaseLoader.countDown();

      for (Future<String> result : results) {
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("value");
      }
    }
    assertThat(loaderCalls).hasValue(1);
  }

  @Test
  void loaderFailureIsReportedAndNotCached() {
    ExpiringLruCache<String, String> cache = cache(10);

    assertThatThrownBy(
            () ->
                cache.get(
                    "k",
                    key -> {
                      throw new IllegalStateException("backend down");
                    }))
        .isInstanceOf(CacheLoadException.class)
        .hasRootCauseMessage("backend down");

    assertThat(cache.get("k", key -> "recovered")).isEqualTo("recovered");
  }

  @Test
  void loaderReturningNullIsAFailure() {
    ExpiringLruCache<String, String> cache = cache(10);

    assertThatThrownBy(() -> cache.get("k", key -> null))
        .isInstanceOf(CacheLoadException.class)
        .hasCauseInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsInvalidConfiguration() {
    assertThatThrownBy(() -> new ExpiringLruCache<>(0, TTL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExpiringLruCache<>(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hitRateOfAnUnusedCacheIsOne() {
    assertThat(new CacheStats(0, 0, 0, 0).hitRate()).isEqualTo(1.0);
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
