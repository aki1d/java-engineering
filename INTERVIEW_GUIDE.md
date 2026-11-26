# Interview guide: java-engineering

Use this guide to understand every implementation well enough to defend it in a technical interview. Each section gives the questions an interviewer is likely to ask, the answer the code supports, alternatives, failure scenarios and an exercise to do yourself.

Read the code first, then try to answer each question before reading the answer.

---

## 1. ExpiringLruCache

**Q: Why does a read take a lock? Reads don't change anything.**
In an access-ordered `LinkedHashMap`, `get` moves the entry to the tail of the linked list, so a read is a structural modification. Without the lock, two concurrent reads could corrupt the list.

**Q: What is a cache stampede and how does the code prevent it?**
When a hot key expires, every concurrent request misses and hits the database at once. The cache keeps a `ConcurrentHashMap<K, CompletableFuture<V>>` of loads in flight. The first thread to `putIfAbsent` a future runs the loader, and the others `join()` that future.

**Q: Why not just `ConcurrentHashMap.computeIfAbsent(key, loader)`?**
`computeIfAbsent` holds a lock on the hash bin while the loader runs. That blocks other keys in the same bin, and a loader that touches the same map can deadlock or throw `IllegalStateException` ("Recursive update").

**Q: Why is the future removed from the in-flight map only after the value is stored?**
In the reverse order, a thread arriving in the gap would find neither a value nor a load in progress and would start a second load.

**Q: Why `now - expiresAt >= 0` instead of `now >= expiresAt`?**
`System.nanoTime()` has an arbitrary origin and can overflow. Only differences between two readings are meaningful. The test `expiryIsCorrectWhenTheTickerOverflows` proves it.

**Q: What happens if the loader throws an `Error`?**
The future is still completed exceptionally, so waiting threads do not block forever, and the `Error` is rethrown as an `Error`, not wrapped.

Failure scenarios: the loader hangs (all waiters for that key hang too, so add a timeout inside the loader); invalidation races with a load (see Known limitations in the README).

Scaling: a single lock does not scale with cores. Answer with lock striping (partition into N sub-caches), and explain that this weakens the global LRU order. Then describe Caffeine's read buffers.

**Exercise:** add `refreshAfterWrite`: serve the stale value while one thread reloads asynchronously. Write a test with the injected ticker.

---

## 2. TaskGraphExecutor

**Q: How do you detect a cycle?**
Kahn's algorithm: repeatedly remove nodes with in-degree 0. If nodes remain at the end, they are on or behind a cycle. O(V + E).

**Q: How does a task start as soon as its dependencies finish?**
Each task's future is `allOf(dependencyFutures).thenApplyAsync(run, executor)`. Topological order guarantees that dependency futures exist when a task is wired.

**Q: What does fail-fast mean here, and what can't it do?**
After the first failure, tasks that have not started see the recorded failure and skip themselves. Tasks already running cannot be stopped safely. `Thread.stop` is gone, and interruption is cooperative. The result future completes only after everything has settled, so no work continues behind the caller's back.

**Q: Why `TaskKey<T>` with a `Class<T>`?**
It is a type-safe heterogeneous container: `result.get(ORDERS)` returns `Integer` without a cast, and a mismatched dependency is rejected at build time.

**Q: Why does `resultOf` reject undeclared dependencies?**
Reading a result you did not declare works only if scheduling happens to have finished that task. It is a latent race, so the code makes it a deterministic error.

**Exercise:** add a per-task timeout and make the test prove that a timed-out task's dependents are skipped.

---

## 3. BoundedParallelRunner and AsyncStage

**Q: Why not use a fixed thread pool of size N to limit concurrency?**
Async stages do not occupy a thread while waiting for I/O. A pool limits *threads*, not *requests in flight*. The runner limits in-flight futures directly.

**Q: Why does `continueLane` use a loop?**
If a future is already complete, `whenComplete` runs the callback immediately on the same stack. With many already-completed futures, recursion would overflow the stack. The loop handles synchronous completions iteratively (test: 200,000 items on one lane).

**Q: Why return `Outcome` values instead of failing the future?**
Batch semantics: one failing item must not discard the results of the others. `Outcome` is sealed, so handling it with `switch` is checked by the compiler.

**Q: Is retry always safe?**
No. Only idempotent operations may be retried, which is why `withRetry` takes a predicate. A retried `POST /payments` without an idempotency key can charge twice. Production retries also need exponential backoff with jitter; this implementation retries immediately.

**Exercise:** add exponential backoff with jitter to `withRetry` using `CompletableFuture.delayedExecutor`.

---

## 4. MicroBatcher

**Q: What are the two flush triggers and why both?**
Size (throughput: amortise the per-call cost) and linger (latency: bound how long one item can wait). Kafka producers use the same pair (`batch.size`, `linger.ms`).

**Q: Why a read-write lock around submit and close?**
Without it, `submit` could pass the `closed` check, `close` could finish draining, and the item would then land in a queue nobody reads. Producers share the read lock; `close` takes the write lock, so when it holds it, no submit is half-done.

**Q: Why does the worker poll in 50 ms slices instead of being interrupted on close?**
Interrupting the worker would also interrupt a running batch handler (for example a JDBC call), turning a graceful shutdown into a failed write.

**Q: What if the handler throws?**
The batch goes to `failureHandler` and the worker continues. One poison batch must not stop all future processing. In production the failure handler would write to a dead-letter store.

**Exercise:** start the linger clock at submission time by storing a timestamp with each item. Measure the change in tail latency.

---

## 5. ResourcePool

**Q: What does the semaphore count?**
Active leases. A new resource is created only when no idle resource is available, so the total number of resources (leased plus idle) can never exceed `maxSize` either. The release path puts the resource back in the idle deque *before* returning the permit, so a waiting caller that gets the permit finds the resource there instead of creating a new one.

**Q: Why LIFO for idle resources?**
The most recently used connection is the most likely to be alive (not timed out by a firewall). Resources at the bottom age and can be evicted.

**Q: What if `create()` fails?**
The permit is released before the exception propagates, so a failing factory cannot permanently shrink the pool (test `creationFailureReleasesThePermit`).

**Q: How is a double return prevented?**
`Lease.close()` uses an `AtomicBoolean` compare-and-set. Returning twice would put the same resource in the deque twice and hand it to two callers.

**Exercise:** add `maxIdleTime`: destroy idle resources older than a threshold during `acquire`.

---

## 6. BoundedBlockingQueue and LockFreeStack

**Q: Why `while (count == 0) await()` and not `if`?**
Spurious wake-ups are allowed, and another consumer may take the item between the signal and this thread reacquiring the lock.

**Q: Why two conditions?**
Producers wait for "not full", consumers for "not empty". With one condition, `signal()` could wake the wrong kind of thread, and `signalAll()` would wake everyone.

**Q: What is the ABA problem, and does the Treiber stack suffer from it?**
A CAS succeeds because the head is "A" again, although in between it was popped, and "A" was freed and reused. In Java, a node cannot be reused while a thread still references it, because the GC keeps it alive and the code never recycles nodes. So ABA cannot happen here.

**Q: Is lock-free always faster?**
No. Under high contention all threads hammer one cache line and most CASes fail. Measure with `LockFreeStackBenchmark`.

---

## 7. AccessLogAnalyzer

**Q: Why virtual threads here?**
Reading files is I/O-bound. A virtual thread per file is simple, and blocking reads unmount the virtual thread instead of tying up an OS thread.

**Q: Then why the semaphore?**
Virtual threads are cheap; file descriptors are not. 100,000 files would otherwise hit the OS limit on open files.

**Q: How is memory bounded?**
Lines are streamed, never collected. Per-file state is O(distinct endpoints), and route normalisation keeps the number of endpoints small.

**Q: Why not `Files.lines(path).parallel()`?**
A single file is read sequentially anyway, so splitting a line stream gains little. Parallelism across files is the natural unit, and it also keeps per-thread state private (no synchronisation).

**Exercise:** add p95 latency with a mergeable histogram (bucketed counts) and prove in a test that merging partial histograms equals a single pass.

---

## 8. Ledger (records, sealed types, immutability)

**Q: Why does `Money` reject `0.001 EUR` instead of rounding?**
Silent rounding loses money. The caller must choose a rounding policy explicitly.

**Q: Why is `replay` a loop and not `stream().reduce()`?**
`reduce` requires an associative accumulator, so that a parallel stream can split the work. Applying events in order is not associative.

**Q: Business exception vs `IllegalArgumentException`?**
`InsufficientFundsException` is an expected outcome that callers handle. A currency mismatch is a bug in the caller, so it is a programming error.

**Q: What does `sealed` give you beyond `final`?**
A closed set of known subtypes. `switch` over them is exhaustive without `default`, so adding a new event type breaks compilation wherever it is not handled.

---

## General questions to prepare

- How would you find a deadlock in production? (`jcmd <pid> Thread.print`, JFR monitor events, `ThreadMXBean.findDeadlockedThreads`.)
- What is a happens-before relationship? Point to examples here: `Thread.start`, `Lock.unlock` → `lock`, `CompletableFuture` completion → dependent action, `volatile closed` in `MicroBatcher`.
- When would you *not* use virtual threads? CPU-bound work, and code that pins carriers or relies heavily on `ThreadLocal` caches.
- How do you test concurrent code? Force interleavings with latches and barriers, use timeouts so deadlocks fail fast, run many iterations, and use jcstress for memory-model questions.
