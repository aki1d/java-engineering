package io.github.aki1d.javaengineering.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class AsyncStageTest {

  @Test
  void composesStagesInOrder() {
    AsyncStage<String, Integer> length = s -> CompletableFuture.completedFuture(s.length());
    AsyncStage<Integer, String> describe = n -> CompletableFuture.completedFuture(n + " chars");

    assertThat(length.then(describe).apply("order").join()).isEqualTo("5 chars");
  }

  @Test
  void retriesRetryableFailuresUntilSuccess() {
    AtomicInteger attempts = new AtomicInteger();
    AsyncStage<String, String> flaky =
        input ->
            attempts.incrementAndGet() < 3
                ? CompletableFuture.failedFuture(new UncheckedIOException(new IOException("reset")))
                : CompletableFuture.completedFuture("ok");

    String result = flaky.withRetry(3, e -> e instanceof UncheckedIOException).apply("x").join();

    assertThat(result).isEqualTo("ok");
    assertThat(attempts).hasValue(3);
  }

  @Test
  void stopsRetryingAfterMaxAttempts() {
    AtomicInteger attempts = new AtomicInteger();
    AsyncStage<String, String> alwaysFails =
        input -> {
          attempts.incrementAndGet();
          throw new UncheckedIOException(new IOException("down"));
        };

    assertThatThrownBy(() -> alwaysFails.withRetry(4, e -> true).apply("x").join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(UncheckedIOException.class);
    assertThat(attempts).hasValue(4);
  }

  @Test
  void doesNotRetryNonRetryableFailures() {
    AtomicInteger attempts = new AtomicInteger();
    AsyncStage<String, String> invalid =
        input -> {
          attempts.incrementAndGet();
          return CompletableFuture.failedFuture(new IllegalArgumentException("bad input"));
        };

    assertThatThrownBy(
            () -> invalid.withRetry(5, e -> e instanceof UncheckedIOException).apply("x").join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(attempts).hasValue(1);
  }

  @Test
  void timesOutSlowStages() {
    AsyncStage<String, String> never = input -> new CompletableFuture<>();

    assertThatThrownBy(() -> never.withTimeout(Duration.ofMillis(20)).apply("x").join())
        .hasCauseInstanceOf(TimeoutException.class);
  }

  @Test
  void timeoutCombinedWithRetryRetriesTimedOutAttempts() {
    AtomicInteger attempts = new AtomicInteger();
    AsyncStage<String, String> slowThenFast =
        input ->
            attempts.incrementAndGet() == 1
                ? new CompletableFuture<>()
                : CompletableFuture.completedFuture("fast");

    String result =
        slowThenFast
            .withTimeout(Duration.ofMillis(20))
            .withRetry(2, e -> e instanceof TimeoutException)
            .apply("x")
            .join();

    assertThat(result).isEqualTo("fast");
  }

  @Test
  void outcomeFailureUnwrapsCompletionExceptions() {
    IllegalStateException root = new IllegalStateException("root");

    Outcome<String> outcome = Outcome.failure(new CompletionException(root));

    assertThat(outcome).isEqualTo(new Outcome.Failure<String>(root));
    assertThat(outcome.isSuccess()).isFalse();
  }

  @Test
  void rejectsInvalidRetryConfiguration() {
    AsyncStage<String, String> stage = CompletableFuture::completedFuture;

    assertThatThrownBy(() -> stage.withRetry(0, e -> true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
