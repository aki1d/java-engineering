package io.github.aki1d.javaengineering.cache;

/**
 * Point-in-time cache counters. Counters are read individually, so a snapshot taken under
 * concurrent load may be slightly inconsistent (for example hits + misses may not match the exact
 * number of completed lookups). That is acceptable for monitoring.
 */
public record CacheStats(long hitCount, long missCount, long loadCount, long evictionCount) {

  public double hitRate() {
    long requests = hitCount + missCount;
    return requests == 0 ? 1.0 : (double) hitCount / requests;
  }
}
