package io.github.aki1d.javaengineering.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class BoundedBlockingQueueTest {

  @Test
  void preservesFifoOrderAcrossWrapAround() throws InterruptedException {
    BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(3);
    List<Integer> taken = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      queue.put(i);
      if (queue.size() == 2) {
        taken.add(queue.take());
      }
    }
    while (!queue.isEmpty()) {
      taken.add(queue.take());
    }

    assertThat(taken).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
  }

  @Test
  void offerTimesOutWhenFullAndPollTimesOutWhenEmpty() throws InterruptedException {
    BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);

    assertThat(queue.poll(Duration.ofMillis(20))).isNull();
    assertThat(queue.offer("a", Duration.ofMillis(20))).isTrue();
    assertThat(queue.offer("b", Duration.ofMillis(20))).isFalse();
    assertThat(queue.offer("c", Duration.ZERO)).isFalse();
  }

  @Test
  void blockedProducerResumesWhenConsumerFreesSpace() throws Exception {
    BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
    queue.put("first");
    CountDownLatch producerStarted = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> producer =
          executor.submit(
              () -> {
                producerStarted.countDown();
                queue.put("second");
                return null;
              });
      producerStarted.await();

      assertThat(queue.take()).isEqualTo("first");
      producer.get(5, TimeUnit.SECONDS);
      assertThat(queue.take()).isEqualTo("second");
    }
  }

  @Test
  void blockedConsumerCanBeInterrupted() throws InterruptedException {
    BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
    List<Throwable> failures = new ArrayList<>();
    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    queue.take();
                  } catch (InterruptedException e) {
                    failures.add(e);
                  }
                });

    consumer.interrupt();
    consumer.join();

    assertThat(failures).singleElement().isInstanceOf(InterruptedException.class);
  }

  @Test
  void everyProducedElementIsConsumedExactlyOnceUnderContention() throws Exception {
    int producers = 8;
    int perProducer = 5_000;
    BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(16, true);
    var consumed = ConcurrentHashMap.<Integer>newKeySet();

    try (ExecutorService executor = Executors.newFixedThreadPool(producers + 4)) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int p = 0; p < producers; p++) {
        int offset = p * perProducer;
        tasks.add(
            executor.submit(
                () -> {
                  for (int i = 0; i < perProducer; i++) {
                    queue.put(offset + i);
                  }
                  return null;
                }));
      }
      int total = producers * perProducer;
      for (int c = 0; c < 4; c++) {
        tasks.add(
            executor.submit(
                () -> {
                  for (int i = 0; i < total / 4; i++) {
                    assertThat(consumed.add(queue.take())).isTrue();
                  }
                  return null;
                }));
      }
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
      assertThat(consumed).hasSize(total);
    }
  }

  @Test
  void drainToMovesAvailableElementsUpToTheLimit() throws InterruptedException {
    BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(5);
    for (int i = 0; i < 5; i++) {
      queue.put(i);
    }
    List<Integer> target = new ArrayList<>();

    assertThat(queue.drainTo(target, 3)).isEqualTo(3);
    assertThat(target).containsExactly(0, 1, 2);
    assertThat(queue.size()).isEqualTo(2);
    assertThat(queue.capacity()).isEqualTo(5);
  }

  @Test
  void rejectsNullElementsAndInvalidCapacity() {
    BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);

    assertThatThrownBy(() -> queue.put(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new BoundedBlockingQueue<String>(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
