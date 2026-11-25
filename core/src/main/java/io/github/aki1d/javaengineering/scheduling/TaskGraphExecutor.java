package io.github.aki1d.javaengineering.scheduling;

import io.github.aki1d.javaengineering.scheduling.TaskGraph.Task;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a {@link TaskGraph} with maximum parallelism: every task starts as soon as all of its
 * dependencies have finished, independent branches run concurrently.
 *
 * <p>Each task becomes a {@link CompletableFuture} that waits on its dependencies' futures with
 * {@code allOf}. Because the graph is processed in topological order, the futures of a task's
 * dependencies always exist by the time the task itself is wired up.
 *
 * <p><b>Failure handling is fail-fast:</b> after the first failure, tasks that have not started yet
 * are skipped. Tasks already running are allowed to finish (Java offers no safe way to stop them),
 * and the returned future completes only once nothing is running any more, so no work leaks past
 * the result.
 */
public final class TaskGraphExecutor {

  private final Executor executor;

  /**
   * @param executor runs the tasks; virtual threads ({@code
   *     Executors.newVirtualThreadPerTaskExecutor()}) suit I/O-bound tasks, a bounded pool suits
   *     CPU-bound ones
   */
  public TaskGraphExecutor(Executor executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * @return a future with all results, or failed with a {@link TaskExecutionException} naming the
   *     first task that failed
   */
  public CompletableFuture<GraphResult> execute(TaskGraph graph) {
    Map<TaskKey<?>, CompletableFuture<?>> futures = new LinkedHashMap<>();
    AtomicReference<TaskExecutionException> firstFailure = new AtomicReference<>();

    for (Task<?> task : graph.topologicalOrder()) {
      Map<TaskKey<?>, CompletableFuture<?>> dependencyFutures = new HashMap<>();
      task.dependencies().forEach(key -> dependencyFutures.put(key, futures.get(key)));
      CompletableFuture<?> future =
          CompletableFuture.allOf(dependencyFutures.values().toArray(CompletableFuture[]::new))
              .thenApplyAsync(
                  ignored -> run(task, Map.copyOf(dependencyFutures), firstFailure), executor);
      futures.put(task.key(), future);
    }

    return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
        .handle(
            (ignored, error) -> {
              if (error != null) {
                TaskExecutionException failure = firstFailure.get();
                // No recorded task failure means the executor itself failed, e.g. it rejected work.
                throw failure != null ? failure : new CompletionException(error);
              }
              Map<TaskKey<?>, Object> results = new HashMap<>();
              futures.forEach((key, future) -> results.put(key, future.join()));
              return new GraphResult(results);
            });
  }

  private static <T> T run(
      Task<T> task,
      Map<TaskKey<?>, CompletableFuture<?>> dependencyFutures,
      AtomicReference<TaskExecutionException> firstFailure) {
    if (firstFailure.get() != null) {
      throw new CancellationException("Skipped after failure of " + firstFailure.get().taskName());
    }
    TaskContext context = new DependencyContext(task.key(), dependencyFutures);
    try {
      return Objects.requireNonNull(
          task.action().apply(context), () -> "Task " + task.key() + " returned null");
    } catch (RuntimeException | Error e) {
      firstFailure.compareAndSet(null, new TaskExecutionException(task.key().name(), e));
      throw e;
    }
  }

  private record DependencyContext(
      TaskKey<?> running, Map<TaskKey<?>, CompletableFuture<?>> dependencyFutures)
      implements TaskContext {

    @Override
    public <T> T resultOf(TaskKey<T> dependency) {
      CompletableFuture<?> future = dependencyFutures.get(dependency);
      if (future == null) {
        throw new IllegalArgumentException(
            "Task %s did not declare a dependency on %s".formatted(running, dependency));
      }
      // Never blocks: this task only starts after all of its dependencies have completed.
      return dependency.type().cast(future.join());
    }
  }
}
