package io.github.aki1d.javaengineering.pool;

import java.io.Serial;

/** Thrown when a resource cannot be acquired from a {@link ResourcePool}. */
public final class PoolException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final Reason reason;

  public enum Reason {
    /** No resource became available before the timeout. */
    TIMEOUT,
    /** The factory failed to create a new resource. */
    CREATION_FAILED,
    /** The pool has been closed. */
    CLOSED
  }

  PoolException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
