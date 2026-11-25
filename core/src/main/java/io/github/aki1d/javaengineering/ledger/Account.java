package io.github.aki1d.javaengineering.ledger;

import io.github.aki1d.javaengineering.ledger.LedgerEvent.AccountFrozen;
import io.github.aki1d.javaengineering.ledger.LedgerEvent.Deposited;
import io.github.aki1d.javaengineering.ledger.LedgerEvent.FeeCharged;
import io.github.aki1d.javaengineering.ledger.LedgerEvent.Withdrawn;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Immutable account state. Every change produces a new instance, which makes the state safe to
 * share between threads and easy to reason about in tests.
 *
 * @param version number of events applied so far; usable for optimistic concurrency checks
 */
public record Account(String id, Money balance, Status status, long version) {

  public enum Status {
    ACTIVE,
    FROZEN
  }

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(balance, "balance");
    Objects.requireNonNull(status, "status");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  public static Account open(String id, Currency currency) {
    return new Account(id, Money.zero(currency), Status.ACTIVE, 0);
  }

  /**
   * Rebuilds the state from its history. This is an ordered left fold, so it is a plain loop:
   * {@code Stream.reduce} requires an associative accumulator, and applying events is not one.
   */
  public static Account replay(String id, Currency currency, List<? extends LedgerEvent> events) {
    Account account = open(id, currency);
    for (LedgerEvent event : events) {
      account = account.apply(event);
    }
    return account;
  }

  public Account apply(LedgerEvent event) {
    Objects.requireNonNull(event, "event");
    return switch (event) {
      case Deposited deposited -> {
        requireActive();
        yield next(balance.plus(deposited.amount()), status);
      }
      case Withdrawn withdrawn -> {
        requireActive();
        if (balance.compareTo(withdrawn.amount()) < 0) {
          throw new InsufficientFundsException(id, balance, withdrawn.amount());
        }
        yield next(balance.minus(withdrawn.amount()), status);
      }
      case FeeCharged fee -> next(balance.minus(fee.amount()), status);
      case AccountFrozen frozen -> next(balance, Status.FROZEN);
    };
  }

  private void requireActive() {
    if (status != Status.ACTIVE) {
      throw new AccountFrozenException(id);
    }
  }

  private Account next(Money newBalance, Status newStatus) {
    return new Account(id, newBalance, newStatus, version + 1);
  }
}
