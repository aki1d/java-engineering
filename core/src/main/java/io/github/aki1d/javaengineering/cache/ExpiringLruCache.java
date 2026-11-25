package io.github.aki1d.javaengineering.cache;

import java.io.Serial;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A thread-safe, size-bounded cache with per-entry time-to-live and single-flight loading.
 *
 * <ul>
 *   <li><b>Bounded size:</b> an access-ordered {@link LinkedHashMap} evicts the least recently used
 *       entry when the cache grows beyond {@code maximumSize}.
 *   <li><b>Expiry:</b> entries expire {@code timeToLive} after they were written. Expired entries
 *       are removed lazily when they are read, or in bulk by {@link #cleanUp()}.
 *   <li><b>Single-flight loading:</b> when many threads miss on the same key at once, only one of
 *       them runs the loader; the others wait for its result. This prevents a cache stampede on the
 *       backing store when a hot key expires.
 * </ul>
 *
 * <p>The map is guarded by one lock, held only for O(1) map operations and never while a loader
 * runs. Slow loads therefore do not block readers of other keys. A single lock does limit
 * throughput on many cores; see {@code docs/cache.md} for the trade-offs and alternatives.
 */
public final class ExpiringLruCache<K, V> {

  private final long timeToLiveNanos;
  private final LongSupplier nanoTicker;
  private final ReentrantLock lock = new ReentrantLock();
  private final AccessOrderedMap<K, V> entries;
  private final ConcurrentHashMap<K, CompletableFuture<V>> loadsInFlight =
      new ConcurrentHashMap<>();

  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder loads = new LongAdder();
  private final LongAdder evictions = new LongAdder();

  public ExpiringLruCache(int maximumSize, Duration timeToLive) {
    this(maximumSize, timeToLive, System::nanoTime);
  }

  /**
   * @param nanoTicker monotonic time source in nanoseconds; injectable so tests can control time
   *     without sleeping
   */
  public ExpiringLruCache(int maximumSize, Duration timeToLive, LongSupplier nanoTicker) {
    if (maximumSize <= 0) {
      throw new IllegalArgumentException("maximumSize must be positive: " + maximumSize);
    }
    if (timeToLive.isNegative() || timeToLive.isZero()) {
      throw new IllegalArgumentException("timeToLive must be positive: " + timeToLive);
    }
    this.timeToLiveNanos = timeToLive.toNanos();
    this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker");
    this.entries = new AccessOrderedMap<>(maximumSize, evictions);
  }

  public Optional<V> getIfPresent(K key) {
    V value = lookup(key);
    recordLookup(value);
    return Optional.ofNullable(value);
  }

  /**
   * Returns the cached value, loading it with {@code loader} on a miss.
   *
   * @throws CacheLoadException if the loader throws or returns {@code null}; every caller waiting
   *     on the same load receives an exception with the same cause
   */
  public V get(K key, Function<? super K, ? extends V> loader) {
    Objects.requireNonNull(loader, "loader");
    V cached = lookup(key);
    recordLookup(cached);
    if (cached != null) {
      return cached;
    }
    CompletableFuture<V> ownLoad = new CompletableFuture<>();
    CompletableFuture<V> runningLoad = loadsInFlight.putIfAbsent(key, ownLoad);
    if (runningLoad != null) {
      return await(runningLoad);
    }
    try {
      ownLoad.complete(loadAndStore(key, loader));
    } catch (RuntimeException | Error e) {
      // Errors too: otherwise threads waiting on this load would block forever.
      ownLoad.completeExceptionally(e);
    } finally {
      // Remove only after the value is stored, so a thread arriving now finds it in the map.
      loadsInFlight.remove(key, ownLoad);
    }
    return await(ownLoad);
  }

  public void put(K key, V value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    store(key, value);
  }

  public void invalidate(K key) {
    lock.lock();
    try {
      entries.remove(key);
    } finally {
      lock.unlock();
    }
  }

  /** Removes all expired entries. O(n); call it periodically if memory must be reclaimed early. */
  public void cleanUp() {
    long now = nanoTicker.getAsLong();
    lock.lock();
    try {
      entries.values().removeIf(entry -> entry.isExpired(now));
    } finally {
      lock.unlock();
    }
  }

  /** Number of stored entries, which may include expired ones not yet cleaned up. */
  public int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  public CacheStats stats() {
    return new CacheStats(hits.sum(), misses.sum(), loads.sum(), evictions.sum());
  }

  private V loadAndStore(K key, Function<? super K, ? extends V> loader) {
    // Another thread may have stored the value between our miss and winning the in-flight slot.
    V existing = lookup(key);
    if (existing != null) {
      return existing;
    }
    V loaded = loader.apply(key);
    if (loaded == null) {
      throw new NullPointerException("Loader returned null for key " + key);
    }
    loads.increment();
    store(key, loaded);
    return loaded;
  }

  private V lookup(K key) {
    Objects.requireNonNull(key, "key");
    long now = nanoTicker.getAsLong();
    lock.lock();
    try {
      Entry<V> entry = entries.get(key);
      if (entry == null) {
        return null;
      }
      if (entry.isExpired(now)) {
        entries.remove(key);
        return null;
      }
      return entry.value();
    } finally {
      lock.unlock();
    }
  }

  private void store(K key, V value) {
    Entry<V> entry = new Entry<>(value, nanoTicker.getAsLong() + timeToLiveNanos);
    lock.lock();
    try {
      entries.put(key, entry);
    } finally {
      lock.unlock();
    }
  }

  private void recordLookup(V value) {
    if (value == null) {
      misses.increment();
    } else {
      hits.increment();
    }
  }

  private static <V> V await(CompletableFuture<V> load) {
    try {
      return load.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw new CacheLoadException("Loading cache value failed", e.getCause());
    }
  }

  private record Entry<V>(V value, long expiresAtNanos) {

    boolean isExpired(long nowNanos) {
      // Compare the difference, not the absolute values: System.nanoTime() may overflow.
      return nowNanos - expiresAtNanos >= 0;
    }
  }

  private static final class AccessOrderedMap<K, V> extends LinkedHashMap<K, Entry<V>> {

    @Serial private static final long serialVersionUID = 1L;
    private static final float LOAD_FACTOR = 0.75f;
    private static final int INITIAL_CAPACITY = 16;

    private final int maximumSize;
    private final LongAdder evictions;

    AccessOrderedMap(int maximumSize, LongAdder evictions) {
      super(INITIAL_CAPACITY, LOAD_FACTOR, true);
      this.maximumSize = maximumSize;
      this.evictions = evictions;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
      boolean evict = size() > maximumSize;
      if (evict) {
        evictions.increment();
      }
      return evict;
    }
  }
}
