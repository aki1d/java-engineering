package io.github.aki1d.javaengineering.ledger;

public final class InsufficientFundsException extends LedgerException {

  private static final long serialVersionUID = 1L;

  public InsufficientFundsException(String accountId, Money balance, Money requested) {
    super(
        accountId,
        "Account %s has %s, cannot withdraw %s".formatted(accountId, balance, requested));
  }
}
