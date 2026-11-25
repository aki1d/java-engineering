package io.github.aki1d.javaengineering.scheduling;

/** Gives a running task access to the results of the tasks it declared as dependencies. */
public interface TaskContext {

  /**
   * @throws IllegalArgumentException if {@code dependency} was not declared by the running task;
   *     reading undeclared results would silently depend on scheduling order
   */
  <T> T resultOf(TaskKey<T> dependency);
}
