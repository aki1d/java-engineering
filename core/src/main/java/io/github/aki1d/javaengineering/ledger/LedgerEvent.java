package io.github.aki1d.javaengineering.ledger;

import java.time.Instant;
import java.util.Objects;

/**
 * Facts recorded against an account. The hierarchy is sealed, so a {@code switch} over it is
 * checked for exhaustiveness by the compiler: adding a new event type breaks every projection that
 * forgot to handle it at compile time, not in production.
 */
public sealed interface LedgerEvent {

  Instant occurredAt();

  record Deposited(Money amount, Instant occurredAt) implements LedgerEvent {
    public Deposited {
      Checks.requirePositive(amount);
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record Withdrawn(Money amount, Instant occurredAt) implements LedgerEvent {
    public Withdrawn {
      Checks.requirePositive(amount);
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  /** Fees are applied even when they overdraw the account. */
  record FeeCharged(Money amount, String reason, Instant occurredAt) implements LedgerEvent {
    public FeeCharged {
      Checks.requirePositive(amount);
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record AccountFrozen(String reason, Instant occurredAt) implements LedgerEvent {
    public AccountFrozen {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }
}
