package io.github.aki1d.javaengineering.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class LockFreeStackTest {

  @Test
  void popsInReverseInsertionOrder() {
    LockFreeStack<String> stack = new LockFreeStack<>();
    stack.push("a");
    stack.push("b");

    assertThat(stack.peek()).contains("b");
    assertThat(stack.pop()).contains("b");
    assertThat(stack.pop()).contains("a");
    assertThat(stack.pop()).isEmpty();
    assertThat(stack.isEmpty()).isTrue();
  }

  @Test
  void concurrentPushesAndPopsNeitherLoseNorDuplicateElements() throws Exception {
    int threads = 8;
    int perThread = 20_000;
    LockFreeStack<Integer> stack = new LockFreeStack<>();
    Set<Integer> popped = ConcurrentHashMap.newKeySet();
    CyclicBarrier start = new CyclicBarrier(threads);

    try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int offset = t * perThread;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  // Interleave pushes and pops so that CAS conflicts actually happen.
                  for (int i = 0; i < perThread; i++) {
                    stack.push(offset + i);
                    if (i % 2 == 0) {
                      stack.pop().ifPresent(value -> assertThat(popped.add(value)).isTrue());
                    }
                  }
                  return null;
                }));
      }
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }

    Optional<Integer> next;
    while ((next = stack.pop()).isPresent()) {
      assertThat(popped.add(next.get())).isTrue();
    }
    assertThat(popped).hasSize(threads * perThread);
  }
}
