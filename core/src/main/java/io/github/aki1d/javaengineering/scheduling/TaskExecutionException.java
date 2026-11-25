package io.github.aki1d.javaengineering.scheduling;

import java.io.Serial;

/** Reports the first task of a graph that failed; its exception is the cause. */
public final class TaskExecutionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String taskName;

  TaskExecutionException(String taskName, Throwable cause) {
    super("Task '%s' failed".formatted(taskName), cause);
    this.taskName = taskName;
  }

  public String taskName() {
    return taskName;
  }
}
