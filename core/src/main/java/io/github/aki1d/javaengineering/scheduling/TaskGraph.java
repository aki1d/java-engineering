package io.github.aki1d.javaengineering.scheduling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * An immutable, validated directed acyclic graph of tasks.
 *
 * <p>Validation happens once, in {@link Builder#build()}: unknown dependencies and cycles are
 * rejected before anything runs, so the executor never has to handle a graph that cannot finish.
 */
public final class TaskGraph {

  record Task<T>(
      TaskKey<T> key, Set<TaskKey<?>> dependencies, Function<TaskContext, ? extends T> action) {}

  private final List<Task<?>> topologicalOrder;

  private TaskGraph(List<Task<?>> topologicalOrder) {
    this.topologicalOrder = List.copyOf(topologicalOrder);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Tasks ordered so that every task appears after all of its dependencies. */
  List<Task<?>> topologicalOrder() {
    return topologicalOrder;
  }

  public List<TaskKey<?>> executionOrder() {
    return topologicalOrder.stream().<TaskKey<?>>map(Task::key).toList();
  }

  public static final class Builder {

    private final Map<String, Task<?>> tasks = new LinkedHashMap<>();

    private Builder() {}

    public <T> Builder task(TaskKey<T> key, Function<TaskContext, ? extends T> action) {
      return task(key, Set.of(), action);
    }

    public <T> Builder task(
        TaskKey<T> key, Set<TaskKey<?>> dependencies, Function<TaskContext, ? extends T> action) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(action, "action");
      if (tasks.containsKey(key.name())) {
        throw new IllegalArgumentException("Duplicate task: " + key.name());
      }
      tasks.put(key.name(), new Task<>(key, Set.copyOf(dependencies), action));
      return this;
    }

    /**
     * Validates the graph and orders it with Kahn's algorithm in O(V + E).
     *
     * @throws IllegalArgumentException for unknown dependencies or dependency cycles
     */
    public TaskGraph build() {
      requireKnownDependencies();
      Map<String, Integer> inDegree = new HashMap<>();
      Map<String, List<String>> dependents = new HashMap<>();
      for (Task<?> task : tasks.values()) {
        inDegree.put(task.key().name(), task.dependencies().size());
        for (TaskKey<?> dependency : task.dependencies()) {
          dependents
              .computeIfAbsent(dependency.name(), name -> new ArrayList<>())
              .add(task.key().name());
        }
      }

      Deque<String> ready = new ArrayDeque<>();
      inDegree.forEach(
          (name, degree) -> {
            if (degree == 0) {
              ready.add(name);
            }
          });
      List<Task<?>> order = new ArrayList<>(tasks.size());
      while (!ready.isEmpty()) {
        String name = ready.poll();
        order.add(tasks.get(name));
        for (String dependent : dependents.getOrDefault(name, List.of())) {
          if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
            ready.add(dependent);
          }
        }
      }

      if (order.size() < tasks.size()) {
        Set<String> blocked = new HashSet<>(tasks.keySet());
        order.forEach(task -> blocked.remove(task.key().name()));
        throw new IllegalArgumentException("Dependency cycle among tasks: " + blocked);
      }
      return new TaskGraph(order);
    }

    private void requireKnownDependencies() {
      for (Task<?> task : tasks.values()) {
        for (TaskKey<?> dependency : task.dependencies()) {
          Task<?> target = tasks.get(dependency.name());
          if (target == null || !target.key().equals(dependency)) {
            throw new IllegalArgumentException(
                "Task %s depends on unknown task %s".formatted(task.key(), dependency));
          }
        }
      }
    }
  }
}
