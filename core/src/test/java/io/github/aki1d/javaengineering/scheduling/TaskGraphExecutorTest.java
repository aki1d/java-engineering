package io.github.aki1d.javaengineering.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class TaskGraphExecutorTest {

  private static final TaskKey<String> CUSTOMER = TaskKey.of("customer", String.class);
  private static final TaskKey<Integer> ORDERS = TaskKey.of("orders", Integer.class);
  private static final TaskKey<Integer> INVOICES = TaskKey.of("invoices", Integer.class);
  private static final TaskKey<String> REPORT = TaskKey.of("report", String.class);

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final TaskGraphExecutor graphExecutor = new TaskGraphExecutor(executor);

  @AfterEach
  void shutDown() {
    executor.close();
  }

  @Test
  void passesDependencyResultsToDependentTasks() {
    TaskGraph graph =
        TaskGraph.builder()
            .task(CUSTOMER, ctx -> "ACME")
            .task(ORDERS, Set.of(CUSTOMER), ctx -> ctx.resultOf(CUSTOMER).length())
            .task(
                REPORT,
                Set.of(CUSTOMER, ORDERS),
                ctx -> ctx.resultOf(CUSTOMER) + " has " + ctx.resultOf(ORDERS) + " orders")
            .build();

    GraphResult result = graphExecutor.execute(graph).join();

    assertThat(result.get(REPORT)).isEqualTo("ACME has 4 orders");
    assertThat(result.get(ORDERS)).isEqualTo(4);
  }

  @Test
  void runsIndependentBranchesConcurrently() {
    // Both tasks wait for each other; this only finishes if they run at the same time.
    CountDownLatch bothStarted = new CountDownLatch(2);
    TaskGraph graph =
        TaskGraph.builder()
            .task(ORDERS, ctx -> rendezvous(bothStarted, 1))
            .task(INVOICES, ctx -> rendezvous(bothStarted, 2))
            .build();

    GraphResult result = graphExecutor.execute(graph).join();

    assertThat(result.get(ORDERS) + result.get(INVOICES)).isEqualTo(3);
  }

  @Test
  void reportsFirstFailureAndSkipsTasksThatHaveNotStarted() {
    AtomicBoolean reportRan = new AtomicBoolean();
    TaskGraph graph =
        TaskGraph.builder()
            .task(
                CUSTOMER,
                ctx -> {
                  throw new IllegalStateException("CRM unavailable");
                })
            .task(ORDERS, Set.of(CUSTOMER), ctx -> 1)
            .task(
                REPORT,
                Set.of(ORDERS),
                ctx -> {
                  reportRan.set(true);
                  return "never";
                })
            .build();

    assertThatThrownBy(() -> graphExecutor.execute(graph).join())
        .isInstanceOf(CompletionException.class)
        .cause()
        .isInstanceOf(TaskExecutionException.class)
        .hasMessageContaining("customer")
        .hasRootCauseMessage("CRM unavailable");
    assertThat(reportRan).isFalse();
  }

  @Test
  void independentTaskQueuedAfterAFailureIsSkipped() {
    // Counts down when any task body has fully returned, i.e. after the executor recorded the
    // failure. The gate task is still running then, so the first finished task is the failing one.
    CountDownLatch firstTaskFinished = new CountDownLatch(1);
    TaskGraphExecutor tracking =
        new TaskGraphExecutor(
            command ->
                executor.execute(
                    () -> {
                      command.run();
                      firstTaskFinished.countDown();
                    }));
    AtomicBoolean lateTaskRan = new AtomicBoolean();
    TaskKey<String> gate = TaskKey.of("gate", String.class);
    TaskGraph graph =
        TaskGraph.builder()
            .task(
                CUSTOMER,
                ctx -> {
                  throw new IllegalStateException("boom");
                })
            .task(gate, ctx -> awaitLatch(firstTaskFinished))
            .task(
                INVOICES,
                Set.of(gate),
                ctx -> {
                  lateTaskRan.set(true);
                  return 0;
                })
            .build();

    var future = tracking.execute(graph);

    assertThatThrownBy(future::join).hasRootCauseMessage("boom");
    assertThat(lateTaskRan).isFalse();
  }

  @Test
  void rejectsUndeclaredDependencyAccess() {
    TaskGraph graph =
        TaskGraph.builder()
            .task(CUSTOMER, ctx -> "ACME")
            .task(ORDERS, ctx -> ctx.resultOf(CUSTOMER).length())
            .build();

    assertThatThrownBy(() -> graphExecutor.execute(graph).join())
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("Task orders did not declare a dependency on customer");
  }

  @Test
  void treatsANullResultAsAFailure() {
    TaskGraph graph = TaskGraph.builder().task(CUSTOMER, ctx -> null).build();

    assertThatThrownBy(() -> graphExecutor.execute(graph).join())
        .hasRootCauseInstanceOf(NullPointerException.class);
  }

  @Test
  void ordersTasksAfterTheirDependencies() {
    TaskGraph graph =
        TaskGraph.builder()
            .task(REPORT, Set.of(ORDERS, INVOICES), ctx -> "r")
            .task(INVOICES, Set.of(CUSTOMER), ctx -> 2)
            .task(ORDERS, Set.of(CUSTOMER), ctx -> 1)
            .task(CUSTOMER, ctx -> "c")
            .build();

    List<TaskKey<?>> order = graph.executionOrder();

    assertThat(order.getFirst()).isEqualTo(CUSTOMER);
    assertThat(order.getLast()).isEqualTo(REPORT);
  }

  @Test
  void rejectsCycles() {
    TaskGraph.Builder builder =
        TaskGraph.builder()
            .task(CUSTOMER, ctx -> "c")
            .task(ORDERS, Set.of(INVOICES), ctx -> 1)
            .task(INVOICES, Set.of(ORDERS), ctx -> 2);

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle")
        .hasMessageContaining("orders")
        .hasMessageContaining("invoices")
        .hasMessageNotContaining("customer");
  }

  @Test
  void rejectsUnknownAndMistypedDependencies() {
    assertThatThrownBy(() -> TaskGraph.builder().task(ORDERS, Set.of(CUSTOMER), ctx -> 1).build())
        .hasMessageContaining("unknown task customer");

    TaskKey<Integer> customerAsNumber = TaskKey.of("customer", Integer.class);
    assertThatThrownBy(
            () ->
                TaskGraph.builder()
                    .task(CUSTOMER, ctx -> "c")
                    .task(ORDERS, Set.of(customerAsNumber), ctx -> 1)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateTaskNames() {
    TaskGraph.Builder builder = TaskGraph.builder().task(CUSTOMER, ctx -> "a");

    assertThatThrownBy(() -> builder.task(CUSTOMER, ctx -> "b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultLookupRejectsUnknownKeys() {
    GraphResult result =
        graphExecutor.execute(TaskGraph.builder().task(CUSTOMER, ctx -> "c").build()).join();

    assertThatThrownBy(() -> result.get(REPORT)).isInstanceOf(IllegalArgumentException.class);
  }

  private static int rendezvous(CountDownLatch latch, int value) {
    latch.countDown();
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Tasks did not run concurrently");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return value;
  }

  private static String awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Latch was not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return "open";
  }
}
