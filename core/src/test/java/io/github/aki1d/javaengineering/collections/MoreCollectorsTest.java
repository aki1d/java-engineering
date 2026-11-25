package io.github.aki1d.javaengineering.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoreCollectorsTest {

  @Test
  void returnsGreatestElementsInDescendingOrder() {
    List<Integer> top =
        Stream.of(5, 1, 9, 3, 7, 2).collect(MoreCollectors.topK(3, Comparator.naturalOrder()));

    assertThat(top).containsExactly(9, 7, 5);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 10, 100, 1_000})
  void parallelResultMatchesSortingTheWholeInput(int k) {
    List<Integer> input = IntStream.range(0, 50_000).map(i -> (i * 7919) % 50_000).boxed().toList();

    List<Integer> parallel =
        input.parallelStream().collect(MoreCollectors.topK(k, Comparator.naturalOrder()));
    List<Integer> expected = input.stream().sorted(Comparator.reverseOrder()).limit(k).toList();

    assertThat(parallel).isEqualTo(expected);
  }

  @Test
  void returnsEverythingWhenKExceedsInputSize() {
    List<String> top =
        Stream.of("b", "a").collect(MoreCollectors.topK(5, Comparator.naturalOrder()));

    assertThat(top).containsExactly("b", "a");
  }

  @Test
  void kOfZeroYieldsAnEmptyImmutableList() {
    List<Integer> top = Stream.of(1, 2).collect(MoreCollectors.topK(0, Comparator.naturalOrder()));

    assertThat(top).isEmpty();
    assertThatThrownBy(() -> top.add(1)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNegativeK() {
    assertThatThrownBy(() -> MoreCollectors.topK(-1, Comparator.<Integer>naturalOrder()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
