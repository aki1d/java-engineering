package io.github.aki1d.javaengineering.ledger;

/**
 * Base type for business rule violations. These are expected outcomes that callers handle, for
 * example by rejecting a request. Programming errors such as a currency mismatch are reported with
 * {@link IllegalArgumentException} instead.
 */
public abstract sealed class LedgerException extends RuntimeException
    permits InsufficientFundsException, AccountFrozenException {

  private static final long serialVersionUID = 1L;

  private final String accountId;

  protected LedgerException(String accountId, String message) {
    super(message);
    this.accountId = accountId;
  }

  public String accountId() {
    return accountId;
  }
}
