package io.github.aki1d.javaengineering.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Applies an {@link AsyncStage} to every input with at most {@code maxConcurrency} items in flight.
 *
 * <p>Starting all items at once would open thousands of connections to a downstream service, and a
 * thread pool cannot limit async stages, because they do not occupy a thread while waiting. So this
 * runner limits <em>in-flight futures</em> instead: it starts {@code maxConcurrency} "lanes", and
 * each lane starts the next item only when its previous item completes. No thread is blocked while
 * waiting.
 *
 * <p>Results keep the order of the inputs, and a failed item becomes an {@link Outcome.Failure}
 * instead of failing the whole batch.
 */
public final class BoundedParallelRunner {

  private final int maxConcurrency;

  public BoundedParallelRunner(int maxConcurrency) {
    if (maxConcurrency <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be positive: " + maxConcurrency);
    }
    this.maxConcurrency = maxConcurrency;
  }

  public <I, O> CompletableFuture<List<Outcome<O>>> runAll(
      List<? extends I> inputs, AsyncStage<? super I, ? extends O> stage) {
    Objects.requireNonNull(stage, "stage");
    List<I> snapshot = List.copyOf(inputs);
    if (snapshot.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    Run<I, O> run = new Run<>(snapshot, stage);
    for (int lane = 0; lane < Math.min(maxConcurrency, snapshot.size()); lane++) {
      run.continueLane();
    }
    return run.completion;
  }

  /** State of one {@link #runAll} call. */
  private static final class Run<I, O> {

    private final List<I> inputs;
    private final AsyncStage<? super I, ? extends O> stage;
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final AtomicInteger remaining;
    private final AtomicReferenceArray<Outcome<O>> outcomes;
    private final CompletableFuture<List<Outcome<O>>> completion = new CompletableFuture<>();

    Run(List<I> inputs, AsyncStage<? super I, ? extends O> stage) {
      this.inputs = inputs;
      this.stage = stage;
      this.remaining = new AtomicInteger(inputs.size());
      this.outcomes = new AtomicReferenceArray<>(inputs.size());
    }

    /**
     * Starts items one after another on this lane. Items that complete synchronously are handled in
     * this loop instead of a callback, so a long run of already-completed futures cannot grow the
     * call stack until it overflows.
     */
    void continueLane() {
      while (true) {
        int index = nextIndex.getAndIncrement();
        if (index >= inputs.size()) {
          return;
        }
        CompletableFuture<? extends O> future = start(inputs.get(index));
        if (future.isDone()) {
          record(index, future);
          continue;
        }
        future.whenComplete(
            (value, error) -> {
              record(index, future);
              continueLane();
            });
        return;
      }
    }

    private CompletableFuture<? extends O> start(I input) {
      try {
        return Objects.requireNonNull(stage.apply(input), "stage returned a null future");
      } catch (RuntimeException e) {
        return CompletableFuture.failedFuture(e);
      }
    }

    private void record(int index, CompletableFuture<? extends O> future) {
      outcomes.set(index, toOutcome(future));
      if (remaining.decrementAndGet() == 0) {
        List<Outcome<O>> ordered = new ArrayList<>(outcomes.length());
        for (int i = 0; i < outcomes.length(); i++) {
          ordered.add(outcomes.get(i));
        }
        completion.complete(List.copyOf(ordered));
      }
    }

    private Outcome<O> toOutcome(CompletableFuture<? extends O> future) {
      return future
          .<Outcome<O>>handle(
              (value, error) -> error == null ? Outcome.success(value) : Outcome.failure(error))
          .join();
    }
  }
}
