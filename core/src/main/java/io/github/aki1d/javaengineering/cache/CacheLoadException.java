package io.github.aki1d.javaengineering.cache;

import java.io.Serial;

/** Thrown when the loader passed to {@link ExpiringLruCache#get} fails. */
public final class CacheLoadException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public CacheLoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
