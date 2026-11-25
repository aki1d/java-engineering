package io.github.aki1d.javaengineering.pool;

import io.github.aki1d.javaengineering.pool.PoolException.Reason;
import java.time.Duration;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bounded pool of expensive resources, created lazily and reused.
 *
 * <p>A {@link Semaphore} with {@code maxSize} permits bounds the number of resources that exist at
 * once: a caller must hold a permit for as long as it holds a resource. Idle resources sit in a
 * LIFO deque, so the most recently used (and most likely still valid) resource is handed out first,
 * and resources at the bottom can age out.
 *
 * <p>Resources are borrowed through a {@link Lease} that is returned with try-with-resources, so a
 * resource is returned even when the caller throws:
 *
 * <pre>{@code
 * try (var lease = pool.acquire(Duration.ofSeconds(1))) {
 *   use(lease.resource());
 * }
 * }</pre>
 */
public final class ResourcePool<R> implements AutoCloseable {

  private final ResourceFactory<R> factory;
  private final Semaphore permits;
  private final Deque<R> idle = new ConcurrentLinkedDeque<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public ResourcePool(int maxSize, ResourceFactory<R> factory) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
    }
    this.factory = Objects.requireNonNull(factory, "factory");
    // Fair semaphore: waiting callers are served in order, so no caller starves under load.
    this.permits = new Semaphore(maxSize, true);
  }

  /**
   * Borrows a resource, waiting up to {@code timeout} for one to become available.
   *
   * @throws PoolException with reason {@code TIMEOUT}, {@code CREATION_FAILED} or {@code CLOSED}
   */
  public Lease<R> acquire(Duration timeout) throws InterruptedException {
    requireOpen();
    if (!permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
      throw new PoolException(Reason.TIMEOUT, "No resource available within " + timeout, null);
    }
    try {
      requireOpen();
      return new Lease<>(this, takeIdleOrCreate());
    } catch (RuntimeException e) {
      permits.release();
      throw e;
    }
  }

  /** Number of resources currently waiting in the pool. */
  public int idleCount() {
    return idle.size();
  }

  /** Number of additional resources that could be leased right now without waiting. */
  public int availablePermits() {
    return permits.availablePermits();
  }

  /**
   * Closes the pool and destroys idle resources. Leased resources are destroyed when their leases
   * are closed. Later {@link #acquire} calls fail with reason {@code CLOSED}.
   */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      R resource;
      while ((resource = idle.pollFirst()) != null) {
        factory.destroy(resource);
      }
    }
  }

  private R takeIdleOrCreate() {
    R resource;
    while ((resource = idle.pollFirst()) != null) {
      if (factory.isValid(resource)) {
        return resource;
      }
      factory.destroy(resource);
    }
    try {
      return Objects.requireNonNull(factory.create(), "factory returned null");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PoolException(Reason.CREATION_FAILED, "Interrupted while creating resource", e);
    } catch (Exception e) {
      throw new PoolException(Reason.CREATION_FAILED, "Could not create resource", e);
    }
  }

  void release(R resource, boolean broken) {
    try {
      if (broken || closed.get()) {
        factory.destroy(resource);
      } else {
        idle.offerFirst(resource);
        // close() may have run between the check and the offer; make sure nothing is left behind.
        if (closed.get() && idle.remove(resource)) {
          factory.destroy(resource);
        }
      }
    } finally {
      permits.release();
    }
  }

  private void requireOpen() {
    if (closed.get()) {
      throw new PoolException(Reason.CLOSED, "Pool is closed", null);
    }
  }

  /** A borrowed resource. Closing the lease returns the resource to the pool exactly once. */
  public static final class Lease<R> implements AutoCloseable {

    private final ResourcePool<R> pool;
    private final R resource;
    private final AtomicBoolean returned = new AtomicBoolean();
    private volatile boolean broken;

    private Lease(ResourcePool<R> pool, R resource) {
      this.pool = pool;
      this.resource = resource;
    }

    public R resource() {
      if (returned.get()) {
        throw new IllegalStateException("Lease has already been returned");
      }
      return resource;
    }

    /** Marks the resource as broken so it is destroyed instead of being reused. */
    public void markBroken() {
      broken = true;
    }

    @Override
    public void close() {
      if (returned.compareAndSet(false, true)) {
        pool.release(resource, broken);
      }
    }
  }
}
