package io.github.aki1d.javaengineering.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class BoundedParallelRunnerTest {

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void shutDown() {
    executor.close();
  }

  @Test
  void keepsInputOrderAndIsolatesFailures() {
    AsyncStage<Integer, String> stage =
        AsyncStage.blocking(
            n -> {
              if (n % 3 == 0) {
                throw new IllegalArgumentException("multiple of three: " + n);
              }
              return "item-" + n;
            },
            executor);

    List<Outcome<String>> outcomes =
        new BoundedParallelRunner(4).runAll(List.of(1, 2, 3, 4), stage).join();

    assertThat(outcomes)
        .containsExactly(
            Outcome.success("item-1"),
            Outcome.success("item-2"),
            outcomes.get(2),
            Outcome.success("item-4"));
    assertThat(outcomes.get(2))
        .isInstanceOfSatisfying(
            Outcome.Failure.class,
            failure ->
                assertThat(failure.error())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("multiple of three: 3"));
  }

  @Test
  void neverRunsMoreThanTheConfiguredNumberOfItemsAtOnce() {
    int limit = 5;
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    AsyncStage<Integer, Integer> stage =
        AsyncStage.blocking(
            n -> {
              maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
              try {
                Thread.sleep(2);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              inFlight.decrementAndGet();
              return n;
            },
            executor);
    List<Integer> inputs = IntStream.range(0, 200).boxed().toList();

    List<Outcome<Integer>> outcomes = new BoundedParallelRunner(limit).runAll(inputs, stage).join();

    assertThat(outcomes).allMatch(Outcome::isSuccess).hasSize(200);
    assertThat(maxInFlight.get()).isBetween(1, limit);
  }

  @Test
  void handlesManySynchronouslyCompletedItemsWithoutStackOverflow() {
    List<Integer> inputs = IntStream.range(0, 200_000).boxed().toList();
    AsyncStage<Integer, Integer> immediate = CompletableFuture::completedFuture;

    List<Outcome<Integer>> outcomes = new BoundedParallelRunner(1).runAll(inputs, immediate).join();

    assertThat(outcomes).hasSize(200_000);
    assertThat(outcomes.getLast()).isEqualTo(Outcome.success(199_999));
  }

  @Test
  void stageThatThrowsSynchronouslyBecomesAFailureOutcome() {
    AsyncStage<String, String> broken =
        input -> {
          throw new IllegalStateException("not async at all");
        };

    List<Outcome<String>> outcomes =
        new BoundedParallelRunner(2).runAll(List.of("a"), broken).join();

    assertThat(outcomes.getFirst().isSuccess()).isFalse();
  }

  @Test
  void emptyInputCompletesImmediately() {
    AsyncStage<String, String> unused = CompletableFuture::completedFuture;

    assertThat(new BoundedParallelRunner(3).runAll(List.of(), unused).join()).isEmpty();
  }

  @Test
  void rejectsNonPositiveConcurrency() {
    assertThatThrownBy(() -> new BoundedParallelRunner(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
