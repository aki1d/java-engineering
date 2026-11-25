package io.github.aki1d.javaengineering.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aki1d.javaengineering.ledger.LedgerEvent.AccountFrozen;
import io.github.aki1d.javaengineering.ledger.LedgerEvent.Deposited;
import io.github.aki1d.javaengineering.ledger.LedgerEvent.FeeCharged;
import io.github.aki1d.javaengineering.ledger.LedgerEvent.Withdrawn;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

  @Test
  void replaysHistoryIntoCurrentBalanceAndVersion() {
    List<LedgerEvent> history =
        List.of(
            new Deposited(Money.of("100.00", "EUR"), NOW),
            new Withdrawn(Money.of("30.00", "EUR"), NOW),
            new FeeCharged(Money.of("1.50", "EUR"), "monthly fee", NOW));

    Account account = Account.replay("acc-1", EUR, history);

    assertThat(account.balance()).isEqualTo(Money.of("68.50", "EUR"));
    assertThat(account.version()).isEqualTo(3);
    assertThat(account.status()).isEqualTo(Account.Status.ACTIVE);
  }

  @Test
  void applyingAnEventLeavesTheOriginalInstanceUnchanged() {
    Account opened = Account.open("acc-1", EUR);

    Account funded = opened.apply(new Deposited(Money.of("10", "EUR"), NOW));

    assertThat(opened.balance()).isEqualTo(Money.zero(EUR));
    assertThat(funded.balance()).isEqualTo(Money.of("10", "EUR"));
  }

  @Test
  void rejectsWithdrawalLargerThanBalance() {
    Account account = Account.open("acc-1", EUR).apply(new Deposited(Money.of("5", "EUR"), NOW));

    assertThatThrownBy(() -> account.apply(new Withdrawn(Money.of("5.01", "EUR"), NOW)))
        .isInstanceOf(InsufficientFundsException.class)
        .hasMessageContaining("acc-1")
        .extracting(e -> ((LedgerException) e).accountId())
        .isEqualTo("acc-1");
  }

  @Test
  void allowsWithdrawingTheExactBalance() {
    Account account = Account.open("acc-1", EUR).apply(new Deposited(Money.of("5", "EUR"), NOW));

    Account emptied = account.apply(new Withdrawn(Money.of("5", "EUR"), NOW));

    assertThat(emptied.balance()).isEqualTo(Money.zero(EUR));
  }

  @Test
  void feesMayOverdrawTheAccount() {
    Account account =
        Account.open("acc-1", EUR).apply(new FeeCharged(Money.of("2", "EUR"), "card fee", NOW));

    assertThat(account.balance().isNegative()).isTrue();
  }

  @Test
  void frozenAccountRejectsMovementsButStillAcceptsFees() {
    Account frozen =
        Account.open("acc-1", EUR)
            .apply(new Deposited(Money.of("50", "EUR"), NOW))
            .apply(new AccountFrozen("fraud review", NOW));

    assertThatThrownBy(() -> frozen.apply(new Deposited(Money.of("1", "EUR"), NOW)))
        .isInstanceOf(AccountFrozenException.class);
    assertThatThrownBy(() -> frozen.apply(new Withdrawn(Money.of("1", "EUR"), NOW)))
        .isInstanceOf(AccountFrozenException.class);
    assertThat(frozen.apply(new FeeCharged(Money.of("1", "EUR"), "review fee", NOW)).balance())
        .isEqualTo(Money.of("49", "EUR"));
  }

  @Test
  void eventsRejectNonPositiveAmounts() {
    assertThatThrownBy(() -> new Deposited(Money.of("0", "EUR"), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Withdrawn(Money.of("-1", "EUR"), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDepositInAnotherCurrency() {
    Account account = Account.open("acc-1", EUR);

    assertThatThrownBy(() -> account.apply(new Deposited(Money.of("1", "USD"), NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Currency mismatch");
  }
}
