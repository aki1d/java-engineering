package io.github.aki1d.javaengineering.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class MicroBatcherTest {

  private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(1);
  private static final ThreadFactory WORKER = Thread.ofPlatform().name("batcher-test").factory();
  private static final BiConsumer<List<Integer>, RuntimeException> FAIL_TEST =
      (batch, e) -> {
        throw new AssertionError("Unexpected batch failure", e);
      };

  private final List<List<Integer>> batches = new CopyOnWriteArrayList<>();

  private MicroBatcher<Integer> batcher(int maxBatchSize, Duration linger) {
    return new MicroBatcher<>(maxBatchSize, linger, 1_000, batches::add, FAIL_TEST, WORKER);
  }

  @Test
  void flushesFullBatchesWithoutWaitingForTheLinger() throws InterruptedException {
    MicroBatcher<Integer> batcher = batcher(3, Duration.ofMinutes(1));

    for (int i = 0; i < 6; i++) {
      batcher.submit(i, SUBMIT_TIMEOUT);
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (batches.size() < 2 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    batcher.close();

    assertThat(batches).containsExactly(List.of(0, 1, 2), List.of(3, 4, 5));
  }

  @Test
  void flushesPartialBatchAfterTheLinger() throws InterruptedException {
    CountDownLatch flushed = new CountDownLatch(1);
    MicroBatcher<Integer> batcher =
        new MicroBatcher<>(
            100,
            Duration.ofMillis(30),
            10,
            batch -> {
              batches.add(batch);
              flushed.countDown();
            },
            FAIL_TEST,
            WORKER);

    batcher.submit(1, SUBMIT_TIMEOUT);

    assertThat(flushed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(batches).containsExactly(List.of(1));
    batcher.close();
  }

  @Test
  void closeFlushesEveryAcceptedItemFromConcurrentProducers() throws Exception {
    MicroBatcher<Integer> batcher = batcher(50, Duration.ofMillis(5));
    int producers = 8;
    int perProducer = 2_000;

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int p = 0; p < producers; p++) {
        int offset = p * perProducer;
        tasks.add(
            executor.submit(
                () -> {
                  for (int i = 0; i < perProducer; i++) {
                    assertThat(batcher.submit(offset + i, SUBMIT_TIMEOUT)).isTrue();
                  }
                  return null;
                }));
      }
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    }
    batcher.close();

    List<Integer> all = batches.stream().flatMap(List::stream).sorted().toList();
    assertThat(all).hasSize(producers * perProducer).doesNotHaveDuplicates();
    assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(50));
  }

  @Test
  void slowConsumerAppliesBackpressureToProducers() throws InterruptedException {
    CountDownLatch releaseConsumer = new CountDownLatch(1);
    Consumer<List<Integer>> blockedHandler =
        batch -> {
          try {
            releaseConsumer.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    MicroBatcher<Integer> batcher =
        new MicroBatcher<>(1, Duration.ofMillis(1), 2, blockedHandler, FAIL_TEST, WORKER);

    // One item is taken by the blocked handler, two more fill the queue.
    List<Boolean> accepted = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      accepted.add(batcher.submit(i, Duration.ofMillis(100)));
    }

    assertThat(accepted).contains(false);
    releaseConsumer.countDown();
    batcher.close();
  }

  @Test
  void handlerFailureIsReportedAndProcessingContinues() throws InterruptedException {
    List<List<Integer>> failed = Collections.synchronizedList(new ArrayList<>());
    MicroBatcher<Integer> batcher =
        new MicroBatcher<>(
            1,
            Duration.ofMillis(5),
            10,
            batch -> {
              if (batch.contains(1)) {
                throw new IllegalStateException("poison item");
              }
              batches.add(batch);
            },
            (batch, e) -> failed.add(batch),
            WORKER);

    batcher.submit(1, SUBMIT_TIMEOUT);
    batcher.submit(2, SUBMIT_TIMEOUT);
    batcher.close();

    assertThat(failed).containsExactly(List.of(1));
    assertThat(batches).containsExactly(List.of(2));
  }

  @Test
  void rejectsSubmissionsAfterClose() {
    MicroBatcher<Integer> batcher = batcher(10, Duration.ofMillis(5));
    batcher.close();

    assertThatThrownBy(() -> batcher.submit(1, SUBMIT_TIMEOUT))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsInvalidConfiguration() {
    assertThatThrownBy(() -> batcher(0, Duration.ofMillis(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> batcher(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
