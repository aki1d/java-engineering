package io.github.aki1d.javaengineering.ledger;

public final class AccountFrozenException extends LedgerException {

  private static final long serialVersionUID = 1L;

  public AccountFrozenException(String accountId) {
    super(accountId, "Account %s is frozen".formatted(accountId));
  }
}
