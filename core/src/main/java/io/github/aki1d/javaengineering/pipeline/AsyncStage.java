package io.github.aki1d.javaengineering.pipeline;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One asynchronous processing step. Stages compose like functions, and cross-cutting behaviour
 * (timeouts, retries) is added by decoration rather than built into each step:
 *
 * <pre>{@code
 * AsyncStage<OrderId, Invoice> pipeline =
 *     fetchOrder.withTimeout(Duration.ofSeconds(2))
 *         .withRetry(3, e -> e instanceof TimeoutException)
 *         .then(AsyncStage.blocking(this::price, cpuPool))
 *         .then(renderInvoice);
 * }</pre>
 */
@FunctionalInterface
public interface AsyncStage<I, O> {

  CompletableFuture<O> apply(I input);

  /**
   * Adapts synchronous (possibly blocking) code by running it on {@code executor}, so it never
   * blocks the thread that completed the previous stage.
   */
  static <I, O> AsyncStage<I, O> blocking(
      Function<? super I, ? extends O> step, Executor executor) {
    Objects.requireNonNull(step, "step");
    Objects.requireNonNull(executor, "executor");
    return input -> CompletableFuture.supplyAsync(() -> step.apply(input), executor);
  }

  default <R> AsyncStage<I, R> then(AsyncStage<? super O, R> next) {
    Objects.requireNonNull(next, "next");
    return input -> applySafely(input).thenCompose(next::apply);
  }

  /**
   * Fails the stage with a {@link java.util.concurrent.TimeoutException} if it does not complete in
   * time. The underlying operation is not cancelled; make it respect its own deadline if it holds
   * scarce resources.
   */
  default AsyncStage<I, O> withTimeout(Duration timeout) {
    long nanos = timeout.toNanos();
    return input -> applySafely(input).orTimeout(nanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Re-runs the stage when it fails with a retryable error, up to {@code maxAttempts} attempts in
   * total. Only retry operations that are safe to repeat (idempotent).
   */
  default AsyncStage<I, O> withRetry(int maxAttempts, Predicate<? super Throwable> retryable) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
    }
    Objects.requireNonNull(retryable, "retryable");
    return input -> attempt(input, 1, maxAttempts, retryable);
  }

  private CompletableFuture<O> attempt(
      I input, int attempt, int maxAttempts, Predicate<? super Throwable> retryable) {
    return applySafely(input)
        .exceptionallyCompose(
            error -> {
              Throwable cause = error instanceof CompletionException ce ? ce.getCause() : error;
              if (attempt < maxAttempts && retryable.test(cause)) {
                return attempt(input, attempt + 1, maxAttempts, retryable);
              }
              return CompletableFuture.failedFuture(cause);
            });
  }

  /** Turns a synchronous throw from {@link #apply} into a failed future. */
  private CompletableFuture<O> applySafely(I input) {
    try {
      return Objects.requireNonNull(apply(input), "stage returned a null future");
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
