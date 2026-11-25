package io.github.aki1d.javaengineering.collections;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.stream.Collector;

/** Collectors that are missing from {@link java.util.stream.Collectors}. */
public final class MoreCollectors {

  private MoreCollectors() {}

  /**
   * Collects the {@code k} greatest elements according to {@code comparator}, greatest first.
   *
   * <p>Sorting the whole stream and taking a prefix costs O(n log n) time and O(n) memory. This
   * collector keeps a min-heap of at most {@code k} elements instead: O(n log k) time and O(k)
   * memory, which matters when {@code n} is large or unbounded and {@code k} is small.
   *
   * <p>The collector supports parallel streams: partial heaps are merged in the combiner. Ties are
   * broken arbitrarily.
   */
  public static <T> Collector<T, ?, List<T>> topK(int k, Comparator<? super T> comparator) {
    if (k < 0) {
      throw new IllegalArgumentException("k must not be negative: " + k);
    }
    Objects.requireNonNull(comparator, "comparator");
    return Collector.of(
        () -> new PriorityQueue<T>(comparator),
        (heap, element) -> offerBounded(heap, element, k, comparator),
        (left, right) -> {
          right.forEach(element -> offerBounded(left, element, k, comparator));
          return left;
        },
        heap -> {
          List<T> result = new ArrayList<>(heap);
          result.sort(comparator.reversed());
          return List.copyOf(result);
        });
  }

  private static <T> void offerBounded(
      PriorityQueue<T> heap, T element, int k, Comparator<? super T> comparator) {
    if (k == 0) {
      return;
    }
    if (heap.size() < k) {
      heap.offer(element);
    } else if (comparator.compare(element, heap.peek()) > 0) {
      heap.poll();
      heap.offer(element);
    }
  }
}
