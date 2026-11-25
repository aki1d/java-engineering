package io.github.aki1d.javaengineering.concurrent;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed-capacity FIFO queue that blocks producers when full and consumers when empty.
 *
 * <p>The design follows the classic monitor pattern: one {@link ReentrantLock} guards a ring
 * buffer, and two {@link Condition}s let producers and consumers wait for different events. With a
 * single condition every {@code signalAll} would wake both producers and consumers, most of whom
 * would immediately go back to sleep.
 *
 * <p>{@code java.util.concurrent.ArrayBlockingQueue} is the production choice; this class exists to
 * show the mechanics and is used by {@link MicroBatcher}. All operations are O(1) except {@link
 * #drainTo}, which is O(number of drained elements).
 *
 * @param <E> element type; {@code null} elements are rejected so that {@code null} can mean "timed
 *     out" in {@link #poll(Duration)}
 */
public final class BoundedBlockingQueue<E> {

  private final Object[] items;
  private final ReentrantLock lock;
  private final Condition notEmpty;
  private final Condition notFull;

  private int head;
  private int tail;
  private int count;

  public BoundedBlockingQueue(int capacity) {
    this(capacity, false);
  }

  /**
   * @param fair whether blocked threads are served in arrival order; fairness prevents starvation
   *     at the cost of throughput
   */
  public BoundedBlockingQueue(int capacity, boolean fair) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.items = new Object[capacity];
    this.lock = new ReentrantLock(fair);
    this.notEmpty = lock.newCondition();
    this.notFull = lock.newCondition();
  }

  /** Inserts the element, waiting as long as necessary for space. */
  public void put(E element) throws InterruptedException {
    Objects.requireNonNull(element, "element");
    lock.lockInterruptibly();
    try {
      // Always wait in a loop: a wake-up does not guarantee the condition still holds.
      while (count == items.length) {
        notFull.await();
      }
      enqueue(element);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Inserts the element, waiting up to {@code timeout} for space.
   *
   * @return {@code false} if the queue stayed full for the whole timeout
   */
  public boolean offer(E element, Duration timeout) throws InterruptedException {
    Objects.requireNonNull(element, "element");
    long remainingNanos = timeout.toNanos();
    lock.lockInterruptibly();
    try {
      while (count == items.length) {
        if (remainingNanos <= 0) {
          return false;
        }
        remainingNanos = notFull.awaitNanos(remainingNanos);
      }
      enqueue(element);
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** Removes the head element, waiting as long as necessary for one to arrive. */
  public E take() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      while (count == 0) {
        notEmpty.await();
      }
      return dequeue();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes the head element, waiting up to {@code timeout} for one to arrive.
   *
   * @return the element, or {@code null} if the queue stayed empty for the whole timeout
   */
  public E poll(Duration timeout) throws InterruptedException {
    return poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  E poll(long timeout, TimeUnit unit) throws InterruptedException {
    long remainingNanos = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (count == 0) {
        if (remainingNanos <= 0) {
          return null;
        }
        remainingNanos = notEmpty.awaitNanos(remainingNanos);
      }
      return dequeue();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Moves up to {@code maxElements} available elements into {@code target} without blocking. This
   * takes the lock once for the whole batch instead of once per element.
   *
   * @return the number of elements moved
   */
  public int drainTo(Collection<? super E> target, int maxElements) {
    Objects.requireNonNull(target, "target");
    lock.lock();
    try {
      int drained = 0;
      while (count > 0 && drained < maxElements) {
        target.add(dequeue());
        drained++;
      }
      return drained;
    } finally {
      lock.unlock();
    }
  }

  public int size() {
    lock.lock();
    try {
      return count;
    } finally {
      lock.unlock();
    }
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  public int capacity() {
    return items.length;
  }

  private void enqueue(E element) {
    items[tail] = element;
    tail = (tail + 1) % items.length;
    count++;
    notEmpty.signal();
  }

  private E dequeue() {
    @SuppressWarnings("unchecked") // only E instances are ever stored
    E element = (E) items[head];
    items[head] = null; // let the GC reclaim the element
    head = (head + 1) % items.length;
    count--;
    notFull.signal();
    return element;
  }
}
