package io.github.aki1d.javaengineering.ledger;

import java.util.Objects;

final class Checks {

  private Checks() {}

  static void requirePositive(Money amount) {
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Amount must be positive: " + amount);
    }
  }
}
