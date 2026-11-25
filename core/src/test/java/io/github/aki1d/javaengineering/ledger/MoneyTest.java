package io.github.aki1d.javaengineering.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

  @ParameterizedTest
  @CsvSource({"10.5, EUR, 10.50", "10, JPY, 10", "7.125, BHD, 7.125"})
  void normalisesScaleToTheCurrencyMinorUnit(String amount, String currency, String expected) {
    Money money = Money.of(amount, currency);

    assertThat(money.amount()).isEqualTo(new BigDecimal(expected));
  }

  @Test
  void equalAmountsWithDifferentInputScaleAreEqual() {
    assertThat(Money.of("10.5", "EUR")).isEqualTo(Money.of("10.50", "EUR"));
    assertThat(Money.of("10.5", "EUR")).hasSameHashCodeAs(Money.of("10.50", "EUR"));
  }

  @Test
  void rejectsAmountsThatWouldNeedRounding() {
    assertThatThrownBy(() -> Money.of("0.001", "EUR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fraction digits");
    assertThatThrownBy(() -> Money.of("1.5", "JPY")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsCurrenciesWithoutAFixedMinorUnit() {
    Currency gold = Currency.getInstance("XAU");

    assertThatThrownBy(() -> Money.zero(gold)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void arithmeticRequiresTheSameCurrency() {
    Money eur = Money.of("1", "EUR");
    Money usd = Money.of("1", "USD");

    assertThatThrownBy(() -> eur.plus(usd)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> eur.minus(usd)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> eur.compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addsSubtractsAndCompares() {
    Money a = Money.of("10.25", "EUR");
    Money b = Money.of("0.75", "EUR");

    assertThat(a.plus(b)).isEqualTo(Money.of("11", "EUR"));
    assertThat(b.minus(a)).isEqualTo(Money.of("-9.5", "EUR"));
    assertThat(a).isGreaterThan(b);
    assertThat(a.toString()).isEqualTo("10.25 EUR");
  }
}
