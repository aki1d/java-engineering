package io.github.aki1d.javaengineering.collections;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * A growable list of primitive {@code int} values.
 *
 * <p>{@code ArrayList<Integer>} stores a reference per element that points to a separate {@code
 * Integer} object (for values outside the small-integer cache). On a typical 64-bit JVM with
 * compressed references that is 4 bytes for the reference plus 16 bytes for the object, compared
 * with 4 bytes here. The elements are also contiguous in memory, which keeps sequential scans
 * cache-friendly and avoids GC pressure from boxing. See {@code IntListSumBenchmark} for a
 * measurement on your own machine.
 *
 * <p>Not thread-safe. Confine an instance to one thread or guard it externally.
 */
public final class IntArrayList {

  private static final int DEFAULT_CAPACITY = 10;
  // Some JVMs reserve header words in arrays; allocating above this limit can fail.
  private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

  private int[] elements;
  private int size;

  public IntArrayList() {
    this(DEFAULT_CAPACITY);
  }

  public IntArrayList(int initialCapacity) {
    if (initialCapacity < 0 || initialCapacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("Illegal capacity: " + initialCapacity);
    }
    this.elements = new int[initialCapacity];
  }

  public static IntArrayList of(int... values) {
    IntArrayList list = new IntArrayList(values.length);
    System.arraycopy(values, 0, list.elements, 0, values.length);
    list.size = values.length;
    return list;
  }

  public void add(int value) {
    ensureCapacity(size + 1);
    elements[size++] = value;
  }

  public int get(int index) {
    Objects.checkIndex(index, size);
    return elements[index];
  }

  public int set(int index, int value) {
    Objects.checkIndex(index, size);
    int previous = elements[index];
    elements[index] = value;
    return previous;
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** Sum as a {@code long} so that adding many large values cannot overflow silently. */
  public long sum() {
    long total = 0;
    for (int i = 0; i < size; i++) {
      total += elements[i];
    }
    return total;
  }

  public void sort() {
    Arrays.sort(elements, 0, size);
  }

  public IntStream stream() {
    return Arrays.stream(elements, 0, size);
  }

  public int[] toArray() {
    return Arrays.copyOf(elements, size);
  }

  /** Releases unused capacity, for example after a list has been filled and will only be read. */
  public void trimToSize() {
    if (elements.length > size) {
      elements = Arrays.copyOf(elements, size);
    }
  }

  int capacity() {
    return elements.length;
  }

  public void ensureCapacity(int minCapacity) {
    if (minCapacity < 0 || minCapacity > MAX_CAPACITY) {
      throw new OutOfMemoryError("Required capacity exceeds the maximum array size");
    }
    if (minCapacity <= elements.length) {
      return;
    }
    // Grow by 50% like ArrayList: amortised O(1) appends with less over-allocation than doubling.
    long grown = (long) elements.length + (elements.length >> 1);
    int newCapacity = (int) Math.min(MAX_CAPACITY, Math.max(grown, minCapacity));
    elements = Arrays.copyOf(elements, Math.max(newCapacity, DEFAULT_CAPACITY));
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof IntArrayList that
        && Arrays.equals(elements, 0, size, that.elements, 0, that.size);
  }

  @Override
  public int hashCode() {
    int result = 1;
    for (int i = 0; i < size; i++) {
      result = 31 * result + elements[i];
    }
    return result;
  }

  @Override
  public String toString() {
    return Arrays.toString(toArray());
  }
}
