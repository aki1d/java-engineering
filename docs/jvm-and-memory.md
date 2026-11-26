# JVM, memory and garbage collection

These notes connect the JVM concepts to concrete code in this repository. They describe general HotSpot behaviour; check the exact defaults for your JDK with `java -XX:+PrintFlagsFinal -version`.

## Where memory goes

| Area | Holds | Relevant code |
|---|---|---|
| Heap: young generation | Short-lived objects: boxed `Integer`s, iterator objects, lambdas that capture variables | `IntListSumBenchmark.boxedLoop` creates none per element; `boxedStream` may, depending on inlining |
| Heap: old generation | Long-lived objects: cache entries, pooled resources | `ExpiringLruCache`, `ResourcePool` |
| Thread stacks | Frames of platform threads (fixed size, ~1 MB default reservation) | `MicroBatcher` worker |
| Heap (virtual threads) | Stack chunks of virtual threads, copied in and out on mount/unmount | `AccessLogAnalyzer` (one virtual thread per file) |
| Metaspace | Class metadata | Grows with generated classes (JMH, proxies, lambdas) |
| Direct memory | NIO buffers outside the heap | `Files.newInputStream` uses an internal buffer; `FileChannel.map` would use direct memory |

## Object layout and why primitives matter

On a 64-bit HotSpot JVM with compressed class pointers and compressed oops (the default below ~32 GB heap):

- Object header: 12 bytes, padded to an 8-byte boundary.
- `Integer`: 12-byte header + 4-byte `int` = 16 bytes.
- Reference: 4 bytes.

So `ArrayList<Integer>` costs about 20 bytes per element (reference + `Integer`), and `IntArrayList` costs 4. More importantly, the `Integer` objects are scattered across the heap. Summing them means one cache miss per element once the data no longer fits in CPU caches. `IntListSumBenchmark` measures this directly.

Use the JOL tool (`org.openjdk.jol`) to print real layouts on your JVM instead of trusting these numbers.

## Garbage collectors

| Collector | Flag | Good for | Trade-off |
|---|---|---|---|
| G1 (default) | `-XX:+UseG1GC` | General server workloads, heaps from a few GB up | Balances throughput and pause targets (`-XX:MaxGCPauseMillis`) |
| ZGC (generational since JDK 21) | `-XX:+UseZGC -XX:+ZGenerational` | Low-latency services, large heaps | Pauses well under a millisecond; uses more CPU and memory headroom |
| Parallel | `-XX:+UseParallelGC` | Batch jobs where only total throughput matters | Longer stop-the-world pauses |
| Serial | `-XX:+UseSerialGC` | Small containers with one CPU | Single-threaded collection |

How the code in this repository is written with GC in mind:

- **Allocation rate.** Short-lived garbage is cheap but not free: it drives young-generation collections. `TrafficSummary` reuses mutable per-endpoint counters instead of allocating per line.
- **Bounded retention.** `ExpiringLruCache`, `BoundedBlockingQueue` and `MicroBatcher` all have hard size limits. An unbounded queue in front of a slow consumer is the most common cause of `OutOfMemoryError` in services.
- **Nulling slots.** `BoundedBlockingQueue.dequeue` clears the array slot so the GC can reclaim the element. A ring buffer that only moves indexes leaks references until the slot is overwritten.
- **Cardinality control.** `AccessLogEntry.endpoint()` collapses `/orders/42` into `/orders/{id}`. Without it, map size grows with the number of distinct IDs, which is a memory leak in disguise.

## Diagnosing memory problems

```bash
# GC log with pause times
java -Xlog:gc*:file=gc.log:time,uptime,level,tags -jar app.jar

# Live histogram of the heap (which classes use the most memory)
jcmd <pid> GC.class_histogram | head -20

# Heap dump for offline analysis in Eclipse MAT or VisualVM
jcmd <pid> GC.heap_dump /tmp/heap.hprof

# Continuous low-overhead profiling (allocation, locks, CPU)
java -XX:StartFlightRecording=duration=60s,filename=rec.jfr -jar app.jar
```

JDK Flight Recorder's "Java Monitor Blocked" events show contention on locks such as the one in `ExpiringLruCache`.

## Virtual threads: what to watch

- Virtual threads are cheap to create, so the scarce resource moves elsewhere (file handles, connections). `AccessLogAnalyzer` therefore still caps open files with a semaphore.
- On JDK 21, blocking inside `synchronized` pins the virtual thread to its carrier. The code here uses `ReentrantLock` in blocking paths. JDK 24 (JEP 491) removed most pinning, but the habit is still sensible on 21.
- `ThreadLocal`s are per virtual thread. With millions of virtual threads, caching expensive objects in `ThreadLocal` multiplies memory use.
