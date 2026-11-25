package io.github.aki1d.javaengineering.scheduling;

import java.util.Map;

/** Results of a successfully executed {@link TaskGraph}. */
public final class GraphResult {

  private final Map<TaskKey<?>, Object> results;

  GraphResult(Map<TaskKey<?>, Object> results) {
    this.results = Map.copyOf(results);
  }

  public <T> T get(TaskKey<T> key) {
    Object value = results.get(key);
    if (value == null && !results.containsKey(key)) {
      throw new IllegalArgumentException("Unknown task: " + key);
    }
    return key.type().cast(value);
  }
}
