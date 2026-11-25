package io.github.aki1d.javaengineering.scheduling;

import java.util.Objects;

/**
 * Typed identifier of a task and its result.
 *
 * <p>Carrying the result type in the key makes {@link TaskContext#resultOf} and {@link
 * GraphResult#get} type-safe without unchecked casts at call sites: a heterogeneous container in
 * the sense of Effective Java, item 33.
 */
public record TaskKey<T>(String name, Class<T> type) {

  public TaskKey {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Task name must not be blank");
    }
  }

  public static <T> TaskKey<T> of(String name, Class<T> type) {
    return new TaskKey<>(name, type);
  }

  @Override
  public String toString() {
    return name;
  }
}
