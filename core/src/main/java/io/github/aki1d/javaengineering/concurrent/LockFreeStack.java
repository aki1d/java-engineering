package io.github.aki1d.javaengineering.concurrent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A lock-free LIFO stack (Treiber stack).
 *
 * <p>Every operation reads the current head, prepares a new head, and publishes it with a single
 * compare-and-set. If another thread changed the head in the meantime the CAS fails and the
 * operation retries. No thread ever blocks, so a thread that is descheduled mid-operation cannot
 * stall the others.
 *
 * <p>The ABA problem does not apply here: nodes are immutable and never reused, and the garbage
 * collector does not free a node while any thread still holds a reference to it. In a language with
 * manual memory management this exact algorithm would need hazard pointers or tagged references.
 *
 * <p>Under heavy contention the retry loop wastes CPU on a single hot cache line, so a lock-based
 * stack or an elimination-backoff stack can be faster. {@code LockFreeStackBenchmark} measures
 * this.
 */
public final class LockFreeStack<E> {

  private record Node<E>(E value, Node<E> next) {}

  private final AtomicReference<Node<E>> head = new AtomicReference<>();

  public void push(E value) {
    Objects.requireNonNull(value, "value");
    Node<E> current;
    Node<E> pushed;
    do {
      current = head.get();
      pushed = new Node<>(value, current);
    } while (!head.compareAndSet(current, pushed));
  }

  public Optional<E> pop() {
    Node<E> current;
    do {
      current = head.get();
      if (current == null) {
        return Optional.empty();
      }
    } while (!head.compareAndSet(current, current.next()));
    return Optional.of(current.value());
  }

  public Optional<E> peek() {
    Node<E> current = head.get();
    return current == null ? Optional.empty() : Optional.of(current.value());
  }

  public boolean isEmpty() {
    return head.get() == null;
  }
}
