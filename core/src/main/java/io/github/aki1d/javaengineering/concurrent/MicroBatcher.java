package io.github.aki1d.javaengineering.concurrent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Groups items submitted by many producer threads into batches handled by one consumer thread.
 *
 * <p>A batch is flushed when it reaches {@code maxBatchSize} or when {@code maxLinger} has passed
 * since its first item arrived, whichever comes first. This is the trade-off behind Kafka's {@code
 * batch.size}/{@code linger.ms}: larger batches amortise per-call overhead (one SQL insert or one
 * HTTP call per batch), and the linger bound caps the latency an item can wait for its batch.
 *
 * <p>The queue between producers and the consumer is bounded, so a slow consumer applies
 * backpressure: {@link #submit} waits for space and gives up after its timeout instead of letting
 * memory grow without limit.
 *
 * <p>{@link #close()} stops accepting items, flushes everything already accepted and waits for the
 * consumer to finish. No accepted item is lost on a clean shutdown.
 */
public final class MicroBatcher<T> implements AutoCloseable {

  /** Upper bound on how long {@link #close()} waits for an idle worker to notice shutdown. */
  private static final long IDLE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

  private final int maxBatchSize;
  private final long maxLingerNanos;
  private final BoundedBlockingQueue<T> queue;
  private final Consumer<List<T>> batchHandler;
  private final BiConsumer<List<T>, RuntimeException> failureHandler;
  private final Thread worker;

  // Producers hold the read lock while enqueuing; close() takes the write lock. Once close()
  // holds it, no producer is half-way through a submit, so the consumer can drain safely.
  private final ReadWriteLock submitLock = new ReentrantReadWriteLock();
  private volatile boolean closed;

  /**
   * @param batchHandler processes one batch; runs on the batcher's worker thread
   * @param failureHandler called with the batch and the exception when {@code batchHandler} throws;
   *     the batcher keeps running afterwards
   */
  public MicroBatcher(
      int maxBatchSize,
      Duration maxLinger,
      int queueCapacity,
      Consumer<List<T>> batchHandler,
      BiConsumer<List<T>, RuntimeException> failureHandler,
      ThreadFactory threadFactory) {
    if (maxBatchSize <= 0) {
      throw new IllegalArgumentException("maxBatchSize must be positive: " + maxBatchSize);
    }
    if (maxLinger.isNegative() || maxLinger.isZero()) {
      throw new IllegalArgumentException("maxLinger must be positive: " + maxLinger);
    }
    this.maxBatchSize = maxBatchSize;
    this.maxLingerNanos = maxLinger.toNanos();
    this.queue = new BoundedBlockingQueue<>(queueCapacity);
    this.batchHandler = Objects.requireNonNull(batchHandler, "batchHandler");
    this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    this.worker = threadFactory.newThread(this::runConsumerLoop);
    this.worker.start();
  }

  /**
   * Submits an item, waiting up to {@code timeout} if the queue is full.
   *
   * @return {@code false} if the queue stayed full for the whole timeout
   * @throws IllegalStateException if the batcher has been closed
   */
  public boolean submit(T item, Duration timeout) throws InterruptedException {
    Objects.requireNonNull(item, "item");
    submitLock.readLock().lockInterruptibly();
    try {
      if (closed) {
        throw new IllegalStateException("MicroBatcher is closed");
      }
      return queue.offer(item, timeout);
    } finally {
      submitLock.readLock().unlock();
    }
  }

  /**
   * Stops accepting items, flushes pending ones and waits for the worker thread to finish.
   *
   * <p>If the calling thread is interrupted while waiting, the shutdown becomes forced: the worker
   * is interrupted, items still queued are dropped, and the caller's interrupt flag is restored.
   */
  @Override
  public void close() {
    // Producers hold the read lock for at most their submit timeout, so this wait is bounded.
    submitLock.writeLock().lock();
    try {
      closed = true;
    } finally {
      submitLock.writeLock().unlock();
    }
    try {
      worker.join();
    } catch (InterruptedException e) {
      worker.interrupt();
      Thread.currentThread().interrupt();
    }
  }

  private void runConsumerLoop() {
    try {
      while (!closed || !queue.isEmpty()) {
        List<T> batch = collectBatch();
        if (!batch.isEmpty()) {
          handle(batch);
        }
      }
    } catch (InterruptedException e) {
      // Interrupting the worker is a forced shutdown: items still queued are dropped.
      Thread.currentThread().interrupt();
    }
  }

  private List<T> collectBatch() throws InterruptedException {
    List<T> batch = new ArrayList<>(maxBatchSize);
    // Wait for the first item in short slices so the loop notices close() promptly even when the
    // linger is long. Interrupting the worker instead would also interrupt a running handler.
    T first = queue.poll(Math.min(maxLingerNanos, IDLE_POLL_NANOS), TimeUnit.NANOSECONDS);
    if (first == null) {
      return batch;
    }
    batch.add(first);
    long deadline = System.nanoTime() + maxLingerNanos;
    while (batch.size() < maxBatchSize) {
      // Take whatever is already queued without waiting, then wait only for the remainder.
      queue.drainTo(batch, maxBatchSize - batch.size());
      long remaining = deadline - System.nanoTime();
      if (batch.size() >= maxBatchSize || remaining <= 0 || closed) {
        break;
      }
      T next = queue.poll(remaining, TimeUnit.NANOSECONDS);
      if (next == null) {
        break;
      }
      batch.add(next);
    }
    return batch;
  }

  private void handle(List<T> batch) {
    List<T> immutableBatch = List.copyOf(batch);
    try {
      batchHandler.accept(immutableBatch);
    } catch (RuntimeException e) {
      failureHandler.accept(immutableBatch, e);
    }
  }
}
