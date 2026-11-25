package io.github.aki1d.javaengineering.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An immutable monetary amount in a single currency.
 *
 * <p>The amount is always stored with the currency's default number of fraction digits, so {@code
 * 10.5 USD} and {@code 10.50 USD} are equal. Values that would need rounding are rejected instead
 * of being rounded silently: money that disappears in a rounding step is a bug, not a detail.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

  public Money {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    int fractionDigits = currency.getDefaultFractionDigits();
    if (fractionDigits < 0) {
      throw new IllegalArgumentException("Currency has no fixed minor unit: " + currency);
    }
    try {
      amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "%s allows %d fraction digits, got %s".formatted(currency, fractionDigits, amount), e);
    }
  }

  public static Money of(String amount, String currencyCode) {
    return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
  }

  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  /**
   * Compares amounts of the same currency.
   *
   * @throws IllegalArgumentException if the currencies differ: amounts in different currencies have
   *     no order without an exchange rate
   */
  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "Currency mismatch: %s vs %s".formatted(currency, other.currency));
    }
  }

  @Override
  public String toString() {
    return amount.toPlainString() + " " + currency.getCurrencyCode();
  }
}
