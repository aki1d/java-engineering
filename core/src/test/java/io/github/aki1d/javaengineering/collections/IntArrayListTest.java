package io.github.aki1d.javaengineering.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IntArrayListTest {

  @Test
  void behavesLikeAnArrayListOfIntegersForRandomOperations() {
    Random random = new Random(42);
    IntArrayList actual = new IntArrayList(0);
    List<Integer> expected = new ArrayList<>();

    for (int i = 0; i < 10_000; i++) {
      int value = random.nextInt();
      if (expected.isEmpty() || random.nextInt(4) > 0) {
        actual.add(value);
        expected.add(value);
      } else {
        int index = random.nextInt(expected.size());
        assertThat(actual.set(index, value)).isEqualTo(expected.set(index, value));
      }
    }

    assertThat(actual.size()).isEqualTo(expected.size());
    assertThat(actual.stream().boxed().toList()).isEqualTo(expected);
    assertThat(actual.sum()).isEqualTo(expected.stream().mapToLong(Integer::longValue).sum());
  }

  @Test
  void sumDoesNotOverflowForLargeValues() {
    IntArrayList list = IntArrayList.of(Integer.MAX_VALUE, Integer.MAX_VALUE);

    assertThat(list.sum()).isEqualTo(2L * Integer.MAX_VALUE);
  }

  @Test
  void growsByHalfAndCanBeTrimmed() {
    IntArrayList list = new IntArrayList(10);
    for (int i = 0; i < 11; i++) {
      list.add(i);
    }

    assertThat(list.capacity()).isEqualTo(15);
    list.trimToSize();
    assertThat(list.capacity()).isEqualTo(11);
    assertThat(list.get(10)).isEqualTo(10);
  }

  @Test
  void rejectsIndexesOutsideTheLogicalSizeEvenWhenCapacityExists() {
    IntArrayList list = new IntArrayList(16);
    list.add(1);

    assertThatThrownBy(() -> list.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> list.set(-1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void rejectsInvalidCapacities() {
    assertThatThrownBy(() -> new IntArrayList(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IntArrayList().ensureCapacity(-5))
        .isInstanceOf(OutOfMemoryError.class);
  }

  @Test
  void sortsOnlyTheUsedPortionOfTheBackingArray() {
    IntArrayList list = new IntArrayList(100);
    list.add(3);
    list.add(-1);
    list.add(2);

    list.sort();

    assertThat(list.toArray()).containsExactly(-1, 2, 3);
  }

  @Test
  void equalityIgnoresUnusedCapacity() {
    IntArrayList small = IntArrayList.of(1, 2, 3);
    IntArrayList large = new IntArrayList(64);
    large.add(1);
    large.add(2);
    large.add(3);

    assertThat(small).isEqualTo(large).hasSameHashCodeAs(large);
    assertThat(small).isNotEqualTo(IntArrayList.of(1, 2));
    assertThat(small.toString()).isEqualTo("[1, 2, 3]");
    assertThat(new IntArrayList().isEmpty()).isTrue();
  }
}
