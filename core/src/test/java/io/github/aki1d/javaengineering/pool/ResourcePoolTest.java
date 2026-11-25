package io.github.aki1d.javaengineering.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.aki1d.javaengineering.pool.PoolException.Reason;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Timeout(10)
@ExtendWith(MockitoExtension.class)
class ResourcePoolTest {

  private static final Duration SHORT = Duration.ofMillis(50);

  @Mock private ResourceFactory<Connection> factory;

  /** Stand-in for an expensive resource; identity matters, content does not. */
  record Connection(int id) {}

  @Test
  void reusesReturnedResourceInsteadOfCreatingANewOne() throws Exception {
    when(factory.create()).thenReturn(new Connection(1));
    when(factory.isValid(new Connection(1))).thenReturn(true);
    ResourcePool<Connection> pool = new ResourcePool<>(2, factory);

    try (var lease = pool.acquire(SHORT)) {
      assertThat(lease.resource()).isEqualTo(new Connection(1));
    }
    try (var lease = pool.acquire(SHORT)) {
      assertThat(lease.resource()).isEqualTo(new Connection(1));
    }

    verify(factory, times(1)).create();
    assertThat(pool.idleCount()).isEqualTo(1);
  }

  @Test
  void replacesIdleResourceThatFailsValidation() throws Exception {
    when(factory.create()).thenReturn(new Connection(1), new Connection(2));
    when(factory.isValid(new Connection(1))).thenReturn(false);
    ResourcePool<Connection> pool = new ResourcePool<>(1, factory);
    pool.acquire(SHORT).close();

    try (var lease = pool.acquire(SHORT)) {
      assertThat(lease.resource()).isEqualTo(new Connection(2));
    }
    verify(factory).destroy(new Connection(1));
  }

  @Test
  void brokenResourceIsDestroyedOnReturn() throws Exception {
    when(factory.create()).thenReturn(new Connection(1));
    ResourcePool<Connection> pool = new ResourcePool<>(1, factory);

    try (var lease = pool.acquire(SHORT)) {
      lease.markBroken();
    }

    verify(factory).destroy(new Connection(1));
    assertThat(pool.idleCount()).isZero();
    assertThat(pool.availablePermits()).isEqualTo(1);
  }

  @Test
  void timesOutWhenAllResourcesAreLeased() throws Exception {
    when(factory.create()).thenReturn(new Connection(1));
    ResourcePool<Connection> pool = new ResourcePool<>(1, factory);

    try (var lease = pool.acquire(SHORT)) {
      assertThat(lease.resource()).isNotNull();
      assertThatThrownBy(() -> pool.acquire(SHORT))
          .isInstanceOf(PoolException.class)
          .extracting(e -> ((PoolException) e).reason())
          .isEqualTo(Reason.TIMEOUT);
    }
  }

  @Test
  void creationFailureReleasesThePermit() throws Exception {
    when(factory.create()).thenThrow(new IOException("connection refused"));
    ResourcePool<Connection> pool = new ResourcePool<>(1, factory);

    assertThatThrownBy(() -> pool.acquire(SHORT))
        .isInstanceOf(PoolException.class)
        .hasRootCauseInstanceOf(IOException.class)
        .extracting(e -> ((PoolException) e).reason())
        .isEqualTo(Reason.CREATION_FAILED);
    assertThat(pool.availablePermits()).isEqualTo(1);
  }

  @Test
  void closingALeaseTwiceReturnsTheResourceOnce() throws Exception {
    when(factory.create()).thenReturn(new Connection(1));
    ResourcePool<Connection> pool = new ResourcePool<>(2, factory);

    var lease = pool.acquire(SHORT);
    lease.close();
    lease.close();

    assertThat(pool.availablePermits()).isEqualTo(2);
    assertThat(pool.idleCount()).isEqualTo(1);
    assertThatThrownBy(lease::resource).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void closeDestroysIdleResourcesAndResourcesReturnedLater() throws Exception {
    when(factory.create()).thenReturn(new Connection(1), new Connection(2));
    ResourcePool<Connection> pool = new ResourcePool<>(2, factory);
    var first = pool.acquire(SHORT);
    var second = pool.acquire(SHORT);
    first.close();

    pool.close();
    verify(factory).destroy(new Connection(1));
    verify(factory, never()).destroy(new Connection(2));

    second.close();
    verify(factory).destroy(new Connection(2));
    assertThatThrownBy(() -> pool.acquire(SHORT))
        .extracting(e -> ((PoolException) e).reason())
        .isEqualTo(Reason.CLOSED);
  }

  @Test
  void neverExceedsMaximumSizeUnderConcurrentLoad() throws Exception {
    int maxSize = 4;
    AtomicInteger created = new AtomicInteger();
    AtomicInteger inUse = new AtomicInteger();
    AtomicInteger maxObservedInUse = new AtomicInteger();
    ResourceFactory<Connection> realFactory =
        new ResourceFactory<>() {
          @Override
          public Connection create() {
            return new Connection(created.incrementAndGet());
          }

          @Override
          public boolean isValid(Connection resource) {
            return true;
          }

          @Override
          public void destroy(Connection resource) {
            // nothing to release for the stand-in resource
          }
        };
    ResourcePool<Connection> pool = new ResourcePool<>(maxSize, realFactory);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        tasks.add(
            executor.submit(
                () -> {
                  try (var lease = pool.acquire(Duration.ofSeconds(5))) {
                    assertThat(lease.resource().id()).isBetween(1, maxSize);
                    int current = inUse.incrementAndGet();
                    maxObservedInUse.accumulateAndGet(current, Math::max);
                    Thread.sleep(1);
                    inUse.decrementAndGet();
                  }
                  return null;
                }));
      }
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    }

    assertThat(maxObservedInUse.get()).isLessThanOrEqualTo(maxSize);
    assertThat(created.get()).isLessThanOrEqualTo(maxSize);
  }
}
